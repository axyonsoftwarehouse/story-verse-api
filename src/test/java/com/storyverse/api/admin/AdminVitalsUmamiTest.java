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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Os Web Vitals contra um servidor falso com a resposta de /performance/stats do Umami 3.4.1. */
class AdminVitalsUmamiTest {

	private static final String SITE = "ac74551e-2634-4e26-8b06-aebb050fc8db";
	// Formato do 3.4.1: p50/p75/p95 por métrica (sem medida vira 0) e count de envios de performance.
	private static final String STATS = """
			{"lcp":{"p50":1800.5,"p75":2712.3,"p95":5100},
			 "inp":{"p50":96,"p75":184,"p95":420},
			 "cls":{"p50":0.01,"p75":0.0625,"p95":0.31},
			 "fcp":{"p50":900,"p75":1300,"p95":2500},
			 "ttfb":{"p50":120,"p75":210,"p95":600},
			 "count":148}
			""";
	private static final String WITHOUT_INTERACTION = """
			{"lcp":{"p50":1500,"p75":1900,"p95":2400},"inp":{"p50":0,"p75":0,"p95":0},
			 "cls":{"p50":0,"p75":0,"p95":0},"fcp":{"p50":0,"p75":0,"p95":0},
			 "ttfb":{"p50":0,"p75":0,"p95":0},"count":3}
			""";
	private static final String EMPTY = """
			{"lcp":{"p50":0,"p75":0,"p95":0},"inp":{"p50":0,"p75":0,"p95":0},"cls":{"p50":0,"p75":0,"p95":0},
			 "fcp":{"p50":0,"p75":0,"p95":0},"ttfb":{"p50":0,"p75":0,"p95":0},"count":0}
			""";

	private HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private volatile String stats = STATS;
	private volatile int statsStatus = 200;

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
		requests.add(path + "?" + exchange.getRequestURI().getRawQuery());
		String body;
		int status = 200;
		if (path.equals("/api/auth/login")) {
			body = "{\"token\":\"token-1\",\"user\":{}}";
		} else if (path.endsWith("/performance/stats")) {
			body = stats;
			status = statsStatus;
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

	private AdminVitalsService service() {
		UmamiApiClient client = new UmamiApiClient("http://127.0.0.1:" + server.getAddress().getPort(),
				"storyverse-api", "senha-de-teste", SITE);
		return new AdminVitalsService(null, null, client, null);
	}

	private static final AdminRange RANGE = new AdminRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"));

	@Test
	void devolveOP75DeLcpInpECls() {
		assertEquals(new AdminVitals.Vitals(2712.3, 184.0, 0.0625, 148), service().fetch(RANGE));
	}

	@Test
	void usaOPeriodoDeSaoPauloEOSiteConfigurado() {
		service().fetch(RANGE);

		long start = LocalDate.parse("2026-10-01").atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli();
		long end = LocalDate.parse("2026-10-04").atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli() - 1;
		String request = requests.stream().filter(r -> r.contains("/performance/stats")).findFirst().orElseThrow();
		assertEquals("/api/websites/" + SITE + "/performance/stats?startAt=" + start + "&endAt=" + end, request);
	}

	@Test
	void semInteracaoOInpFicaNuloEOClsZeroContinua() {
		stats = WITHOUT_INTERACTION;
		assertEquals(new AdminVitals.Vitals(1900.0, null, 0.0, 3), service().fetch(RANGE),
				"o Umami troca INP ausente por 0; CLS 0 é medida de verdade");
	}

	@Test
	void semEnviosNoPeriodoNaoInventaNumero() {
		stats = EMPTY;
		assertEquals(new AdminVitals.Vitals(null, null, null, 0), service().fetch(RANGE));
	}

	@Test
	void erroDoUmamiFalhaSemVazarASenha() {
		statsStatus = 500;
		UmamiApiClient.UmamiUnavailable error = assertThrows(UmamiApiClient.UmamiUnavailable.class,
				() -> service().fetch(RANGE));
		assertTrue(error.getMessage().contains("/performance/stats"), error.getMessage());
		assertFalse(error.getMessage().contains("senha-de-teste"));
	}
}
