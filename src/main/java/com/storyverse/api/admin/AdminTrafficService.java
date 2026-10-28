package com.storyverse.api.admin;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

import tools.jackson.databind.JsonNode;

/**
 * Tráfego do site lido do Umami no servidor. Só superadmin, período das métricas (datas de São
 * Paulo, máximo de 90 dias) e cache de 5 minutos; falha fica em cache por 1 minuto.
 */
@Service
public class AdminTrafficService {

	private static final Logger log = LoggerFactory.getLogger(AdminTrafficService.class);
	private static final String TIMEZONE = "America/Sao_Paulo";
	/** Teto para todas as chamadas juntas (cada uma tem timeout próprio de 5s). */
	private static final long DEADLINE_SECONDS = 8;
	private static final int TOP = 10;

	private record Result(AdminTraffic.Traffic traffic, String error) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final UmamiApiClient umami;
	private final TransactionTemplate readOnly;
	private final ShortCache ok = new ShortCache(Duration.ofMinutes(5));
	private final ShortCache failed = new ShortCache(Duration.ofMinutes(1));
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public AdminTrafficService(AuthService auth, AuthzService authz, UmamiApiClient umami,
			PlatformTransactionManager transactions) {
		this.auth = auth;
		this.authz = authz;
		this.umami = umami;
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	/** A permissão é checada numa transação curta; as chamadas ao Umami acontecem fora dela. */
	public AdminTraffic traffic(String token, String fromRaw, String toRaw) {
		AdminRange range = readOnly.execute(status -> {
			UUID actor = auth.authenticate(token);
			authz.requireRole(actor, AuthzService.SUPERADMIN);
			return AdminRange.parse(fromRaw, toRaw);
		});
		Result result = result(range);
		return new AdminTraffic(range.from(), range.to(), result.traffic(), result.error());
	}

	private Result result(AdminRange range) {
		if (!umami.configured()) {
			return new Result(null, "Umami não configurado.");
		}
		String key = range.from() + "|" + range.to();
		Result cached = ok.get(key);
		if (cached == null) cached = failed.get(key);
		if (cached != null) return cached;
		Result result;
		try {
			result = new Result(fetch(range), null);
			ok.put(key, result);
		} catch (RuntimeException exception) {
			log.warn("Umami indisponível para o painel: {}", exception.getMessage());
			result = new Result(null, "Umami indisponível no momento.");
			failed.put(key, result);
		}
		return result;
	}

	AdminTraffic.Traffic fetch(AdminRange range) {
		long startAt = range.from().atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli();
		long endAt = range.to().plusDays(1).atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli() - 1;
		String window = "startAt=" + startAt + "&endAt=" + endAt;
		CompletableFuture<JsonNode> stats = async(() -> umami.website("/stats?" + window));
		CompletableFuture<JsonNode> pageviews = async(() -> umami.website("/pageviews?" + window
				+ "&unit=day&timezone=" + SentryApiClient.encode(TIMEZONE)));
		Map<String, CompletableFuture<JsonNode>> metrics = new LinkedHashMap<>();
		for (String type : List.of("path", "referrer", "device", "country")) {
			metrics.put(type, async(() -> umami.website("/metrics?" + window + "&type=" + type + "&limit=" + TOP)));
		}
		// "Agora" é complemento: se falhar, o resto do tráfego sai mesmo assim.
		CompletableFuture<JsonNode> active = async(() -> umami.website("/active")).exceptionally(error -> null);
		List<CompletableFuture<?>> all = new ArrayList<>(List.of(stats, pageviews, active));
		all.addAll(metrics.values());
		await(CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)));

		JsonNode totals = stats.join();
		long visits = number(totals.path("visits"));
		JsonNode comparison = totals.path("comparison");
		return new AdminTraffic.Traffic(
				number(totals.path("visitors")),
				number(totals.path("pageviews")),
				visits,
				visits == 0 ? 0 : (double) number(totals.path("bounces")) / visits,
				visits == 0 ? 0 : Math.round((double) number(totals.path("totaltime")) / visits),
				activeNow(active.join()),
				new AdminTraffic.Totals(number(comparison.path("visitors")), number(comparison.path("pageviews")),
						number(comparison.path("visits"))),
				series(pageviews.join(), range),
				entries(metrics.get("path").join()),
				entries(metrics.get("referrer").join()),
				entries(metrics.get("device").join()),
				entries(metrics.get("country").join()));
	}

	/** Um ponto por dia do período (dia sem visita vira 0); {@code sessions} são os visitantes. */
	static List<AdminTraffic.Point> series(JsonNode body, AdminRange range) {
		Map<String, Long> views = byDay(body.path("pageviews"));
		Map<String, Long> visitors = byDay(body.path("sessions"));
		List<AdminTraffic.Point> out = new ArrayList<>();
		for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
			String key = day.toString();
			out.add(new AdminTraffic.Point(key, visitors.getOrDefault(key, 0L), views.getOrDefault(key, 0L)));
		}
		return out;
	}

	static List<AdminTraffic.Entry> entries(JsonNode body) {
		List<AdminTraffic.Entry> out = new ArrayList<>();
		if (!body.isArray()) return out;
		for (JsonNode row : body) {
			if (out.size() == TOP) break;
			String value = text(row.path("x"));
			out.add(new AdminTraffic.Entry(value == null ? "" : value, number(row.path("y"))));
		}
		return out;
	}

	/** Umami 3 devolve {@code {visitors: n}}; o 2 devolvia {@code {x: n}}. */
	static long activeNow(JsonNode body) {
		if (body == null) return 0;
		JsonNode visitors = body.path("visitors");
		return visitors.isMissingNode() ? number(body.path("x")) : number(visitors);
	}

	private static Map<String, Long> byDay(JsonNode points) {
		Map<String, Long> out = new LinkedHashMap<>();
		for (JsonNode point : points) {
			String x = text(point.path("x"));
			if (x != null && x.length() >= 10) out.merge(x.substring(0, 10), number(point.path("y")), Long::sum);
		}
		return out;
	}

	private static String text(JsonNode node) {
		return node == null || !node.isValueNode() || node.isNull() ? null : node.asText();
	}

	private static long number(JsonNode node) {
		if (node.isNumber()) return node.asLong();
		String value = text(node);
		try {
			return value == null ? 0 : Math.round(Double.parseDouble(value));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private CompletableFuture<JsonNode> async(Supplier<JsonNode> call) {
		return CompletableFuture.supplyAsync(call, executor);
	}

	private static void await(CompletableFuture<Void> all) {
		try {
			all.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException exception) {
			all.cancel(true);
			throw new UmamiApiClient.UmamiUnavailable("tempo esgotado");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new UmamiApiClient.UmamiUnavailable("interrompido");
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			throw new UmamiApiClient.UmamiUnavailable(cause == null ? "falha" : cause.getMessage());
		}
	}
}
