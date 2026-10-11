package com.story_magic.api.integrations.gutenberg;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.story_magic.api.integrations.ExternalServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GutenbergContentClientTests {

	private static final String BASE_TEXT = """
			*** START OF THIS PROJECT GUTENBERG EBOOK ***
			Texto público.
			[Illustration: capa]
			Fim do conteúdo.
			*** END OF THIS PROJECT GUTENBERG EBOOK ***
			Licença não deve ser incluída.
			""";

	private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private volatile int primaryStatus;
	private volatile int mirrorStatus;
	private volatile String primaryBody;
	private volatile String mirrorBody;
	private volatile long responseDelayMillis;
	private GutenbergContentClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/primary/", exchange -> respond(
				exchange, primaryStatus, primaryBody, responseDelayMillis));
		server.createContext("/mirror/", exchange -> respond(
				exchange, mirrorStatus, mirrorBody, responseDelayMillis));
		server.start();
		primaryStatus = 200;
		mirrorStatus = 200;
		primaryBody = BASE_TEXT;
		mirrorBody = BASE_TEXT;
		client = createClient(Duration.ofSeconds(2));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void stripsLicenseMarkersAndIllustrationPlaceholders() {
		assertEquals("Texto público.\nFim do conteúdo.", client.fetchPlainText(67740));
		assertEquals(List.of("/primary/67740/pg67740.txt"), requestedPaths);
	}

	@Test
	void retriesTheMirrorWhenThePrimaryReturnsAnError() {
		primaryStatus = 503;
		mirrorBody = "Texto do espelho";

		assertEquals("Texto do espelho", client.fetchPlainText(345));
		assertEquals(List.of("/primary/345/pg345.txt", "/mirror/345/pg345.txt"), requestedPaths);
	}

	@Test
	void retriesTheMirrorWhenThePrimaryReturnsHtml() {
		primaryBody = "<!doctype html><html>not a book</html>";
		mirrorBody = "Texto do espelho";

		assertEquals("Texto do espelho", client.fetchPlainText(345));
		assertEquals(2, requestedPaths.size());
	}

	@Test
	void reportsBadGatewayWhenBothSourcesFail() {
		primaryStatus = 404;
		mirrorStatus = 502;

		ExternalServiceException exception = assertThrows(
				ExternalServiceException.class, () -> client.fetchPlainText(999));

		assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
		assertEquals(2, requestedPaths.size());
	}

	@Test
	void reportsGatewayTimeoutWhenAllSourcesTimeOut() {
		responseDelayMillis = 250;
		client = createClient(Duration.ofMillis(40));

		ExternalServiceException exception = assertThrows(
				ExternalServiceException.class, () -> client.fetchPlainText(67740));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatus());
	}

	private GutenbergContentClient createClient(Duration timeout) {
		String origin = "http://127.0.0.1:" + server.getAddress().getPort();
		return new GutenbergContentClient(
				HttpClient.newHttpClient(),
				List.of(origin + "/primary/", origin + "/mirror/"),
				timeout);
	}

	private void respond(
			com.sun.net.httpserver.HttpExchange exchange,
			int status,
			String body,
			long delayMillis) throws IOException {
		requestedPaths.add(exchange.getRequestURI().getPath());
		if (delayMillis > 0) {
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		try {
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		} catch (IOException ignored) {
			// The client may have timed out while this local test server was waiting.
		} finally {
			exchange.close();
		}
	}
}
