package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** O resumo do Sentry contra um servidor falso com as respostas da API REST ({@code /api/0/}). */
class AdminHealthSentryTest {

	private static final String PROJECTS = """
			[{"id":"11","slug":"storyverse-web"},{"id":"22","slug":"storyverse-api"},{"id":"33","slug":"outro"}]
			""";
	/** stats_v2 de um projeto, agrupado só por outcome: assim o Sentry manda a série por dia. */
	private static final String STATS_WEB = """
			{"intervals":["2026-10-25T00:00:00Z","2026-10-26T00:00:00Z"],
			 "groups":[
			  {"by":{"outcome":"accepted"},"totals":{"sum(quantity)":7},"series":{"sum(quantity)":[3,4]}},
			  {"by":{"outcome":"filtered"},"totals":{"sum(quantity)":1},"series":{"sum(quantity)":[1,0]}},
			  {"by":{"outcome":"rate_limited"},"totals":{"sum(quantity)":18},"series":{"sum(quantity)":[9,9]}}]}
			""";
	private static final String STATS_API = """
			{"intervals":["2026-10-25T00:00:00Z","2026-10-26T00:00:00Z"],
			 "groups":[{"by":{"outcome":"accepted"},"totals":{"sum(quantity)":2},"series":{"sum(quantity)":[0,2]}}]}
			""";
	/** Com groupBy=project o Sentry real devolve só os totais, sem intervals nem series. */
	private static final String STATS_ONLY_TOTALS = """
			{"groups":[{"by":{"outcome":"accepted","project":11},"totals":{"sum(quantity)":7}}]}
			""";
	private static final String SESSIONS = """
			{"intervals":["2026-10-25T00:00:00Z","2026-10-26T00:00:00Z"],
			 "groups":[{"by":{},"totals":{"sum(session)":1640,"crash_free_rate(session)":0.993},
			            "series":{"sum(session)":[820,820],"crash_free_rate(session)":[0.99,0.996]}}]}
			""";
	private static final String ISSUES = """
			[{"id":"123","title":"TypeError: x is undefined","count":"31","userCount":9,
			  "lastSeen":"2026-10-26T10:00:00Z","permalink":"https://acme.sentry.io/issues/123/",
			  "culprit":"src/app.tsx","metadata":{"value":"segredo"}}]
			""";

	private static final String YESTERDAY = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();

	private HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private volatile boolean statsFail;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/0/organizations/acme/", this::handle);
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getRawQuery();
		requests.add(path + "?" + query);
		authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
		String body;
		int status = 200;
		if (path.endsWith("/projects/")) {
			body = PROJECTS;
		} else if (path.endsWith("/stats_v2/")) {
			body = query.contains("groupBy=project") ? STATS_ONLY_TOTALS
					: query.contains("project=11") ? STATS_WEB : STATS_API;
			if (statsFail) status = 500;
		} else if (path.endsWith("/events/")) {
			body = "{\"data\":[{\"timestamp.to_day\":\"" + YESTERDAY + "T00:00:00+00:00\",\"count()\":5}],\"meta\":{}}";
		} else if (path.endsWith("/sessions/")) {
			// Projeto de servidor sem sessões: o Sentry recusa; o painel segue sem crash-free.
			body = query.contains("project=11") ? SESSIONS : "{\"detail\":\"no sessions\"}";
			if (!query.contains("project=11")) status = 400;
		} else if (path.endsWith("/issues/")) {
			body = query.contains("project=11") ? ISSUES : "[]";
		} else {
			body = "{}";
			status = 404;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private AdminHealthService service(String projects) {
		return service(projects, "");
	}

	private AdminHealthService service(String projects, String environment) {
		SentryApiClient client = new SentryApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
				"token-de-teste", "acme", projects, environment);
		return new AdminHealthService(null, null, null, client, null, null);
	}

	@Test
	void resumePorProjetoNaOrdemConfigurada() {
		AdminHealth.Sentry sentry = service("storyverse-web, storyverse-api").fetch("14d");

		assertEquals(List.of("storyverse-web", "storyverse-api"),
				sentry.projects().stream().map(AdminHealth.Project::slug).toList());
		AdminHealth.Project web = sentry.projects().get(0);
		assertEquals(List.of(new AdminHealth.ErrorsPoint("2026-10-25", 3, 1L), new AdminHealth.ErrorsPoint("2026-10-26", 4, 0L)),
				web.errorsSeries(), "aceitos e filtrados; rate_limited fica de fora");
		assertEquals(7, web.errorsTotal());
		assertEquals(0.993, web.crashFreeSessions());
		assertEquals(List.of(new AdminHealth.SessionsPoint("2026-10-25", 820), new AdminHealth.SessionsPoint("2026-10-26", 820)),
				web.sessionsSeries());
		assertEquals(List.of(new AdminHealth.Issue("123", "TypeError: x is undefined", 31, 9,
				"2026-10-26T10:00:00Z", "https://acme.sentry.io/issues/123/")), web.topIssues());

		AdminHealth.Project api = sentry.projects().get(1);
		assertEquals(2, api.errorsTotal());
		assertNull(api.crashFreeSessions(), "sem sessões na API: null, não 0");
		assertTrue(api.sessionsSeries().isEmpty());
		assertTrue(api.topIssues().isEmpty());
	}

	@Test
	void chamaAApiComTokenEParametrosEsperados() {
		service("storyverse-web").fetch("24h");

		assertTrue(authorizations.stream().allMatch("Bearer token-de-teste"::equals));
		String stats = requests.stream().filter(r -> r.contains("/stats_v2/")).findFirst().orElseThrow();
		assertTrue(stats.contains("category=error") && stats.contains("groupBy=outcome") && !stats.contains("groupBy=project")
				&& stats.contains("statsPeriod=24h") && stats.contains("project=11"), stats);
		String issues = requests.stream().filter(r -> r.contains("/issues/")).findFirst().orElseThrow();
		assertTrue(issues.contains("query=is%3Aunresolved") && issues.contains("sort=freq")
				&& issues.contains("limit=5") && issues.contains("project=11"), issues);
		String sessions = requests.stream().filter(r -> r.contains("/sessions/")).findFirst().orElseThrow();
		assertTrue(sessions.contains("crash_free_rate(session)") || sessions.contains("crash_free_rate%28session%29"), sessions);
	}

	@Test
	void umaChamadaDeStatsPorProjeto() {
		service("storyverse-web, storyverse-api").fetch("7d");

		List<String> stats = requests.stream().filter(r -> r.contains("/stats_v2/")).toList();
		assertEquals(2, stats.size(), stats.toString());
		assertTrue(stats.stream().anyMatch(r -> r.endsWith("project=11")) && stats.stream().anyMatch(r -> r.endsWith("project=22")),
				stats.toString());
	}

	@Test
	void projetoInexistenteNoSentryFalha() {
		assertThrows(SentryApiClient.SentryUnavailable.class, () -> service("nao-existe").fetch("14d"));
	}

	@Test
	void erroHttpDoSentryFalhaSemVazarOToken() {
		statsFail = true;
		SentryApiClient.SentryUnavailable error = assertThrows(SentryApiClient.SentryUnavailable.class,
				() -> service("storyverse-web").fetch("14d"));
		assertFalse(error.getMessage().contains("token-de-teste"));
	}

	@Test
	void semTokenOrgOuProjetosNaoEstaConfigurado() {
		assertFalse(new SentryApiClient("", "", "acme", "web", "").configured());
		assertFalse(new SentryApiClient("", "t", "", "web", "").configured());
		assertFalse(new SentryApiClient("", "t", "acme", " , ", "").configured());
		assertTrue(new SentryApiClient("", "t", "acme", "web", "").configured());
	}

	/** QA e produção nos mesmos projetos: com ambiente, nada de outro ambiente entra na conta. */
	@Test
	void comAmbienteFiltraTodasAsChamadasELeErrosDosEventos() {
		AdminHealth.Project web = service("storyverse-web", "qa").fetch("7d").projects().get(0);

		assertTrue(requests.stream().noneMatch(r -> r.contains("/stats_v2/")),
				"stats_v2 não separa por ambiente: " + requests);
		assertTrue(requests.stream().filter(r -> !r.contains("/projects/")).allMatch(r -> r.endsWith("environment=qa")),
				"eventos, sessões e issues filtram o ambiente: " + requests);
		String events = requests.stream().filter(r -> r.contains("/events/")).findFirst().orElseThrow();
		assertTrue(events.contains("dataset=errors") && events.contains("statsPeriod=7d") && events.contains("project=11"), events);
		assertEquals(8, web.errorsSeries().size(), "7 dias atrás até hoje, preenchendo com zero");
		assertEquals(5, web.errorsTotal());
		assertTrue(web.errorsSeries().contains(new AdminHealth.ErrorsPoint(YESTERDAY, 5, null)),
				"o dia com eventos traz a contagem e filtered nulo: " + web.errorsSeries());
		assertTrue(web.errorsSeries().stream().allMatch(p -> p.filtered() == null));
		assertEquals(0.993, web.crashFreeSessions());
		assertEquals(1, web.topIssues().size());
	}

	@Test
	void diasRecentesCobremOPeriodoEmUtc() {
		java.time.Instant now = java.time.Instant.parse("2026-10-26T02:00:00Z");
		assertEquals(List.of("2026-10-25", "2026-10-26"), AdminHealthService.recentDays("24h", now));
		assertEquals(15, AdminHealthService.recentDays("14d", now).size());
		assertEquals("2026-10-19", AdminHealthService.recentDays("7d", now).get(0));
	}
}
