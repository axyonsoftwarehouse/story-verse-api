package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** O resumo de tráfego contra um servidor falso com as respostas do Umami 3 (conferidas no 3.4.1). */
class AdminTrafficUmamiTest {

	private static final String SITE = "ac74551e-2634-4e26-8b06-aebb050fc8db";
	private static final String STATS = """
			{"pageviews":540,"visitors":120,"visits":160,"bounces":40,"totaltime":33600,
			 "comparison":{"pageviews":400,"visitors":100,"visits":130,"bounces":30,"totaltime":20000}}
			""";
	private static final String PAGEVIEWS = """
			{"pageviews":[{"x":"2026-10-02 00:00:00","y":70},{"x":"2026-10-03 00:00:00","y":30}],
			 "sessions":[{"x":"2026-10-02 00:00:00","y":18},{"x":"2026-10-03 00:00:00","y":9}]}
			""";

	private HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private final AtomicInteger logins = new AtomicInteger();
	private volatile boolean expireFirstToken;
	private volatile boolean statsFail;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/", this::handle);
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
		String body;
		int status = 200;
		if (path.equals("/api/auth/login")) {
			String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int n = logins.incrementAndGet();
			body = sent.contains("\"storyverse-api\"") && sent.contains("\"senha-de-teste\"")
					? "{\"token\":\"token-" + n + "\",\"user\":{}}"
					: "{}";
			if (body.equals("{}")) status = 401;
		} else {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			authorizations.add(auth);
			if (expireFirstToken && "Bearer token-1".equals(auth)) {
				status = 401;
				body = "{}";
			} else if (path.endsWith("/stats")) {
				body = STATS;
				if (statsFail) status = 500;
			} else if (path.endsWith("/pageviews")) {
				body = PAGEVIEWS;
			} else if (path.endsWith("/metrics")) {
				body = switch (query.replaceAll(".*type=([a-z]+).*", "$1")) {
					case "path" -> "[{\"x\":\"/biblioteca\",\"y\":90},{\"x\":\"/\",\"y\":60}]";
					case "referrer" -> "[{\"x\":\"\",\"y\":50},{\"x\":\"google.com\",\"y\":30}]";
					case "device" -> "[{\"x\":\"mobile\",\"y\":80}]";
					case "country" -> "[{\"x\":\"BR\",\"y\":110}]";
					default -> "[]";
				};
			} else if (path.endsWith("/active")) {
				body = "{\"visitors\":7}";
			} else {
				body = "{}";
				status = 404;
			}
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private AdminTrafficService service() {
		UmamiApiClient client = new UmamiApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
				"storyverse-api", "senha-de-teste", SITE);
		return new AdminTrafficService(null, null, client, null);
	}

	private static final AdminRange RANGE = new AdminRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"));

	@Test
	void resumeTotaisSerieEListas() {
		AdminTraffic.Traffic traffic = service().fetch(RANGE);

		assertEquals(120, traffic.visitors());
		assertEquals(540, traffic.pageviews());
		assertEquals(160, traffic.visits());
		assertEquals(0.25, traffic.bounceRate());
		assertEquals(210, traffic.avgVisitSeconds(), "totaltime ÷ visitas");
		assertEquals(7, traffic.activeNow());
		assertEquals(new AdminTraffic.Totals(100, 400, 130), traffic.previous());
		assertEquals(List.of(new AdminTraffic.Point("2026-10-01", 0, 0), new AdminTraffic.Point("2026-10-02", 18, 70),
				new AdminTraffic.Point("2026-10-03", 9, 30)), traffic.series(), "um ponto por dia, com zero no dia vazio");
		assertEquals(List.of(new AdminTraffic.Entry("/biblioteca", 90), new AdminTraffic.Entry("/", 60)), traffic.topPages());
		assertEquals(new AdminTraffic.Entry("", 50), traffic.referrers().get(0), "acesso direto vem com valor vazio");
		assertEquals(List.of(new AdminTraffic.Entry("mobile", 80)), traffic.devices());
		assertEquals(List.of(new AdminTraffic.Entry("BR", 110)), traffic.countries());
	}

	@Test
	void usaOPeriodoDeSaoPauloEOSiteConfigurado() {
		service().fetch(RANGE);

		long start = LocalDate.parse("2026-10-01").atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli();
		long end = LocalDate.parse("2026-10-04").atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli() - 1;
		String stats = requests.stream().filter(r -> r.contains("/stats")).findFirst().orElseThrow();
		assertTrue(stats.startsWith("/api/websites/" + SITE + "/stats"), stats);
		assertTrue(stats.contains("startAt=" + start) && stats.contains("endAt=" + end), stats);
		String series = requests.stream().filter(r -> r.contains("/pageviews")).findFirst().orElseThrow();
		assertTrue(series.contains("unit=day") && series.contains("timezone=America%2FSao_Paulo"), series);
		assertTrue(requests.stream().anyMatch(r -> r.contains("type=path")), "Umami 3 usa path (url dá 400)");
		assertEquals(1, logins.get(), "um login só, o token é reaproveitado");
		assertTrue(authorizations.stream().allMatch("Bearer token-1"::equals));
	}

	@Test
	void tokenExpiradoFazNovoLogin() {
		expireFirstToken = true;
		AdminTraffic.Traffic traffic = service().fetch(RANGE);

		assertEquals(120, traffic.visitors());
		assertTrue(logins.get() >= 2, "o 401 dispara um login novo");
	}

	@Test
	void erroDoUmamiFalhaSemVazarASenha() {
		statsFail = true;
		UmamiApiClient.UmamiUnavailable error = assertThrows(UmamiApiClient.UmamiUnavailable.class,
				() -> service().fetch(RANGE));
		assertFalse(error.getMessage().contains("senha-de-teste"));
	}

	@Test
	void semUrlUsuarioSenhaOuSiteNaoEstaConfigurado() {
		assertFalse(new UmamiApiClient("", "u", "p", SITE).configured());
		assertFalse(new UmamiApiClient("http://umami:3000", "", "p", SITE).configured());
		assertFalse(new UmamiApiClient("http://umami:3000", "u", "", SITE).configured());
		assertFalse(new UmamiApiClient("http://umami:3000", "u", "p", " ").configured());
		assertTrue(new UmamiApiClient("http://umami:3000", "u", "p", SITE).configured());
	}

	@Test
	void ativosAceitaOFormatoDoUmami2() {
		assertEquals(3, AdminTrafficService.activeNow(tools.jackson.databind.json.JsonMapper.builder().build()
				.readTree("{\"x\":3}")));
	}
}
