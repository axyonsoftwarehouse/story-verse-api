package com.story_magic.api.integrations.gutenberg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.story_magic.api.integrations.ExternalServiceException;

@Component
public class GutenbergContentClient implements BookContentProvider {

	private static final List<String> BASE_URLS = List.of(
			"https://www.gutenberg.org/cache/epub/",
			"https://aleph.pglaf.org/cache/epub/");
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final String USER_AGENT = "StoryMagic/1.0 (public-domain ebook reader)";

	private final HttpClient httpClient;
	private final List<String> baseUrls;
	private final Duration requestTimeout;

	public GutenbergContentClient() {
		this(HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build(), BASE_URLS, REQUEST_TIMEOUT);
	}

	GutenbergContentClient(HttpClient httpClient, List<String> baseUrls, Duration requestTimeout) {
		this.httpClient = httpClient;
		this.baseUrls = List.copyOf(baseUrls);
		this.requestTimeout = requestTimeout;
	}

	@Override
	public String fetchPlainText(int gutenbergId) {
		Exception lastFailure = null;
		boolean timedOut = false;

		for (String baseUrl : baseUrls) {
			try {
				String text = fetchFrom(baseUrl, gutenbergId);
				if (text != null) {
					return stripBoilerplate(text);
				}
			} catch (HttpTimeoutException exception) {
				lastFailure = exception;
				timedOut = true;
			} catch (IOException exception) {
				lastFailure = exception;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new ExternalServiceException(
						HttpStatus.BAD_GATEWAY,
						"A consulta ao Project Gutenberg foi interrompida.",
						exception);
			}
		}

		if (timedOut) {
			throw new ExternalServiceException(
					HttpStatus.GATEWAY_TIMEOUT,
					"O Project Gutenberg demorou demais para responder.",
					lastFailure);
		}
		throw new ExternalServiceException(
				HttpStatus.BAD_GATEWAY,
				"Não foi possível obter o texto do livro no Project Gutenberg.",
				lastFailure);
	}

	private String fetchFrom(String baseUrl, int gutenbergId) throws IOException, InterruptedException {
		URI uri = URI.create(baseUrl + gutenbergId + "/pg" + gutenbergId + ".txt");
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(requestTimeout)
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || isHtml(response.body())) {
			return null;
		}
		return response.body();
	}

	private static boolean isHtml(String body) {
		return body.stripLeading().regionMatches(true, 0, "<!doctype", 0, 9)
				|| body.stripLeading().regionMatches(true, 0, "<html", 0, 5);
	}

	private static String stripBoilerplate(String rawText) {
		String text = rawText.replace("\r\n", "\n");
		int startMarker = findMarker(text, "START OF (THE|THIS) PROJECT GUTENBERG");
		if (startMarker >= 0) {
			int contentStart = text.indexOf('\n', startMarker);
			text = contentStart >= 0 ? text.substring(contentStart + 1) : "";
		}
		int end = findMarker(text, "END OF (THE|THIS) PROJECT GUTENBERG");
		if (end >= 0) {
			text = text.substring(0, end);
		}
		return text.replaceAll("(?im)^\\[Illustration[^\\]]*\\]\\s*", "").strip();
	}

	private static int findMarker(String text, String marker) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
				"(?im)^\\*{3}\\s*" + marker + ".*$").matcher(text);
		return matcher.find() ? matcher.start() : -1;
	}
}
