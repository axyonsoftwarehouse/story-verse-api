package com.storyverse.api.admin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.JsonNode;

/**
 * Saúde do sistema: números do Sentry lidos no servidor + último backup. Só superadmin, cache de
 * 5 minutos. Se o Sentry falhar, devolve {@code sentry: null} com o motivo (cacheado por 1 minuto,
 * para o painel não esperar o timeout a cada recarga).
 */
@Service
public class AdminHealthService {

	private static final Logger log = LoggerFactory.getLogger(AdminHealthService.class);
	private static final Set<String> PERIODS = Set.of("24h", "7d", "14d");
	/** Teto para todas as chamadas ao Sentry juntas (cada uma tem timeout próprio de 5s). */
	private static final long DEADLINE_SECONDS = 8;
	private static final int TOP_ISSUES = 5;

	private record SentryResult(AdminHealth.Sentry sentry, String error) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final AdminOverviewService overview;
	private final SentryApiClient sentry;
	private final JdbcClient jdbc;
	private final TransactionTemplate readOnly;
	private final ShortCache ok = new ShortCache(Duration.ofMinutes(5));
	private final ShortCache failed = new ShortCache(Duration.ofMinutes(1));
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public AdminHealthService(AuthService auth, AuthzService authz, AdminOverviewService overview,
			SentryApiClient sentry, JdbcClient jdbc, PlatformTransactionManager transactions) {
		this.auth = auth;
		this.authz = authz;
		this.overview = overview;
		this.sentry = sentry;
		this.jdbc = jdbc;
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	/** O banco fica numa transação curta; as chamadas ao Sentry acontecem fora dela. */
	public AdminHealth health(String token, String periodRaw) {
		String period = periodRaw == null || periodRaw.isBlank() ? "14d" : periodRaw.strip();
		AdminOverview.Backup backup = readOnly.execute(status -> {
			UUID actor = auth.authenticate(token);
			authz.requireRole(actor, AuthzService.SUPERADMIN);
			if (!PERIODS.contains(period)) {
				throw new AuthException(400, "Período inválido. Use 24h, 7d ou 14d.");
			}
			DbSession.setUser(jdbc, actor);
			return overview.backup();
		});
		SentryResult result = sentry(period);
		return new AdminHealth(period, result.sentry(), result.error(), backup);
	}

	private SentryResult sentry(String period) {
		if (!sentry.configured()) {
			return new SentryResult(null, "Sentry não configurado.");
		}
		SentryResult cached = ok.get(period);
		if (cached == null) cached = failed.get(period);
		if (cached != null) return cached;
		SentryResult result;
		try {
			result = new SentryResult(fetch(period), null);
			ok.put(period, result);
		} catch (RuntimeException exception) {
			log.warn("Sentry indisponível para o painel: {}", exception.getMessage());
			result = new SentryResult(null, "Sentry indisponível no momento.");
			failed.put(period, result);
		}
		return result;
	}

	AdminHealth.Sentry fetch(String period) {
		Map<String, String> ids = projectIds();
		CompletableFuture<JsonNode> stats = async(() -> sentry.organization(statsPath(period, ids.values())));
		Map<String, CompletableFuture<JsonNode>> sessions = new LinkedHashMap<>();
		Map<String, CompletableFuture<JsonNode>> issues = new LinkedHashMap<>();
		ids.forEach((slug, id) -> {
			// Projeto de servidor não tem sessões: falha aqui não derruba o resto.
			sessions.put(slug, async(() -> sentry.organization(sessionsPath(period, id)))
					.exceptionally(error -> null));
			issues.put(slug, async(() -> sentry.organization(issuesPath(period, id))));
		});
		List<CompletableFuture<?>> all = new ArrayList<>(List.of(stats));
		all.addAll(sessions.values());
		all.addAll(issues.values());
		await(CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)));

		JsonNode statsBody = stats.join();
		List<AdminHealth.Project> projects = new ArrayList<>();
		ids.forEach((slug, id) -> {
			List<AdminHealth.ErrorsPoint> errors = errorsSeries(statsBody, id);
			long total = errors.stream().mapToLong(AdminHealth.ErrorsPoint::accepted).sum();
			JsonNode sessionBody = sessions.get(slug).join();
			projects.add(new AdminHealth.Project(slug, errors, total, crashFree(sessionBody),
					sessionsSeries(sessionBody), topIssues(issues.get(slug).join())));
		});
		return new AdminHealth.Sentry(projects);
	}

	/** slug → id numérico, na ordem de SENTRY_PROJECTS. */
	private Map<String, String> projectIds() {
		JsonNode list = sentry.organization("/projects/?per_page=100");
		Map<String, String> found = new LinkedHashMap<>();
		if (list.isArray()) {
			for (JsonNode project : list) {
				found.put(text(project.path("slug")), text(project.path("id")));
			}
		}
		Map<String, String> ids = new LinkedHashMap<>();
		for (String slug : sentry.projects()) {
			String id = found.get(slug);
			if (id == null) throw new SentryApiClient.SentryUnavailable("projeto não encontrado: " + slug);
			ids.put(slug, id);
		}
		return ids;
	}

	static String statsPath(String period, java.util.Collection<String> projectIds) {
		StringBuilder path = new StringBuilder("/stats_v2/?field=sum(quantity)&category=error")
				.append("&groupBy=outcome&groupBy=project&interval=1d&statsPeriod=").append(period);
		projectIds.forEach(id -> path.append("&project=").append(SentryApiClient.encode(id)));
		return path.toString();
	}

	static String sessionsPath(String period, String projectId) {
		return "/sessions/?field=sum(session)&field=crash_free_rate(session)&interval=1d&statsPeriod="
				+ period + "&project=" + SentryApiClient.encode(projectId);
	}

	static String issuesPath(String period, String projectId) {
		return "/issues/?query=" + SentryApiClient.encode("is:unresolved") + "&sort=freq&limit=" + TOP_ISSUES
				+ "&statsPeriod=" + period + "&project=" + SentryApiClient.encode(projectId);
	}

	/** stats_v2 agrupado por outcome e projeto; aceita/filtrada por dia. */
	static List<AdminHealth.ErrorsPoint> errorsSeries(JsonNode body, String projectId) {
		List<String> days = days(body);
		long[] accepted = new long[days.size()];
		long[] filtered = new long[days.size()];
		for (JsonNode group : body.path("groups")) {
			JsonNode by = group.path("by");
			if (!projectId.equals(text(by.path("project")))) continue;
			String outcome = text(by.path("outcome"));
			long[] target = "accepted".equals(outcome) ? accepted : "filtered".equals(outcome) ? filtered : null;
			if (target == null) continue;
			JsonNode series = group.path("series").path("sum(quantity)");
			for (int i = 0; i < days.size(); i++) target[i] += series.path(i).asLong(0);
		}
		List<AdminHealth.ErrorsPoint> out = new ArrayList<>();
		for (int i = 0; i < days.size(); i++) {
			out.add(new AdminHealth.ErrorsPoint(days.get(i), accepted[i], filtered[i]));
		}
		return out;
	}

	static Double crashFree(JsonNode body) {
		if (body == null) return null;
		JsonNode rate = body.path("groups").path(0).path("totals").path("crash_free_rate(session)");
		return rate.isNumber() ? rate.asDouble() : null;
	}

	static List<AdminHealth.SessionsPoint> sessionsSeries(JsonNode body) {
		if (body == null) return List.of();
		List<String> days = days(body);
		JsonNode series = body.path("groups").path(0).path("series").path("sum(session)");
		List<AdminHealth.SessionsPoint> out = new ArrayList<>();
		for (int i = 0; i < days.size(); i++) {
			out.add(new AdminHealth.SessionsPoint(days.get(i), series.path(i).asLong(0)));
		}
		return out;
	}

	/** Só título, contagem, usuários, lastSeen e permalink: o resto do issue fica no Sentry. */
	static List<AdminHealth.Issue> topIssues(JsonNode body) {
		List<AdminHealth.Issue> out = new ArrayList<>();
		if (!body.isArray()) return out;
		for (JsonNode issue : body) {
			if (out.size() == TOP_ISSUES) break;
			out.add(new AdminHealth.Issue(text(issue.path("id")), text(issue.path("title")),
					number(issue.path("count")), number(issue.path("userCount")),
					text(issue.path("lastSeen")), text(issue.path("permalink"))));
		}
		return out;
	}

	private static List<String> days(JsonNode body) {
		List<String> days = new ArrayList<>();
		for (JsonNode interval : body.path("intervals")) {
			String value = text(interval);
			days.add(value == null || value.length() < 10 ? value : value.substring(0, 10));
		}
		return days;
	}

	private static String text(JsonNode node) {
		return node == null || !node.isValueNode() || node.isNull() ? null : node.asText();
	}

	/** O Sentry manda {@code count} como texto e {@code userCount} como número. */
	private static long number(JsonNode node) {
		if (node.isNumber()) return node.asLong();
		String value = text(node);
		try {
			return value == null ? 0 : Long.parseLong(value);
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private CompletableFuture<JsonNode> async(java.util.function.Supplier<JsonNode> call) {
		return CompletableFuture.supplyAsync(call, executor);
	}

	private static void await(CompletableFuture<Void> all) {
		try {
			all.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			all.cancel(true);
			throw new SentryApiClient.SentryUnavailable("tempo esgotado");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SentryApiClient.SentryUnavailable("interrompido");
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			throw new SentryApiClient.SentryUnavailable(cause == null ? "falha" : cause.getMessage());
		}
	}
}
