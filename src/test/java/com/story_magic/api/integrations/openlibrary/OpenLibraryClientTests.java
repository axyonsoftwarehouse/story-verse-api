package com.story_magic.api.integrations.openlibrary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLibraryClientTests {

	private static final String RESPONSE = """
			{
			  "docs": [{
			    "title": "Twilight",
			    "author_name": ["Stephenie Meyer"],
			    "first_publish_year": 2005,
			    "cover_i": 54321,
			    "edition_count": 20,
			    "editions": {
			      "docs": [{
			        "title": "Crepúsculo",
			        "language": ["por"],
			        "cover_i": 12345
			      }]
			    }
			  }]
			}
			""";

	private final List<String> requestUris = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private volatile int responseStatus;
	private volatile String responseBody;
	private volatile long responseDelayMillis;
	private OpenLibraryClient client;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/search.json", exchange -> {
			requestUris.add(exchange.getRequestURI().toString());
			if (responseDelayMillis > 0) {
				try {
					Thread.sleep(responseDelayMillis);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			byte[] body = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			try {
				exchange.sendResponseHeaders(responseStatus, body.length);
				exchange.getResponseBody().write(body);
			} catch (IOException ignored) {
				// The client may have timed out while this local test server was waiting.
			} finally {
				exchange.close();
			}
		});
		server.start();
		responseStatus = 200;
		responseBody = RESPONSE;
		client = new OpenLibraryClient(
				HttpClient.newHttpClient(),
				URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search.json"),
				Duration.ofSeconds(2));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void parsesOpenLibrarySnakeCaseFields() {
		List<OpenLibraryBookDocument> documents = client.search("crepusculo");

		assertEquals(1, documents.size());
		assertEquals("Stephenie Meyer", documents.getFirst().author_name().getFirst());
		assertEquals(2005, documents.getFirst().first_publish_year());
		assertEquals("Crepúsculo", documents.getFirst().editions().docs().getFirst().title());
		assertEquals("por", documents.getFirst().editions().docs().getFirst().language().getFirst());
	}

	@Test
	void encodesSearchTermAndRequestsExpectedFieldsAndLanguage() {
		client.search("love & war");

		String requestUri = requestUris.getFirst();
		assertTrue(requestUri.contains("q=love+%26+war"));
		assertTrue(requestUri.contains("fields="));
		assertTrue(requestUri.contains("limit=20"));
		assertTrue(requestUri.endsWith("lang=pt"));
	}

	@Test
	void returnsAnEmptyListWhenTheProviderOmitsDocs() {
		responseBody = "{}";

		assertEquals(List.of(), client.search("unknown"));
	}

	@Test
	void mapsProviderHttpErrorsToBadGateway() {
		responseStatus = 503;

		ExternalServiceException exception = assertThrows(
				ExternalServiceException.class, () -> client.search("query"));

		assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
		assertTrue(exception.getMessage().contains("503"));
	}

	@Test
	void mapsMalformedProviderJsonToBadGateway() {
		responseBody = "{invalid";

		ExternalServiceException exception = assertThrows(
				ExternalServiceException.class, () -> client.search("query"));

		assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
		assertTrue(exception.getMessage().contains("inválida"));
	}

	@Test
	void mapsRequestTimeoutToGatewayTimeout() {
		responseDelayMillis = 500;
		client = new OpenLibraryClient(
				HttpClient.newHttpClient(),
				URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search.json"),
				Duration.ofMillis(50));

		ExternalServiceException exception = assertThrows(
				ExternalServiceException.class, () -> client.search("query"));

		assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatus());
	}

	@Test
	void preservesThreadInterruptionWhenTheRequestIsInterrupted() {
		Thread.currentThread().interrupt();
		try {
			ExternalServiceException exception = assertThrows(
					ExternalServiceException.class, () -> client.search("query"));

			assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			assertTrue(Thread.interrupted());
		}
		assertFalse(Thread.currentThread().isInterrupted());
	}
}
