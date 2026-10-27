package com.storyverse.api.admin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura da API REST do Sentry ({@code /api/0/}) com o token de organização do servidor.
 * O token nunca sai da API: o painel só vê o resumo montado pelo {@link AdminHealthService}.
 */
@Component
public class SentryApiClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private final String baseUrl;
	private final String token;
	private final String org;
	private final List<String> projects;

	public SentryApiClient(
			@Value("${app.sentry-api.url:https://sentry.io}") String baseUrl,
			@Value("${app.sentry-api.token:}") String token,
			@Value("${app.sentry-api.org:}") String org,
			@Value("${app.sentry-api.projects:}") String projects) {
		String url = baseUrl == null || baseUrl.isBlank() ? "https://sentry.io" : baseUrl.strip();
		this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		this.token = token == null ? "" : token.strip();
		this.org = org == null ? "" : org.strip();
		this.projects = projects == null ? List.of()
				: Arrays.stream(projects.split(",")).map(String::strip).filter(slug -> !slug.isEmpty()).toList();
	}

	public boolean configured() {
		return !token.isEmpty() && !org.isEmpty() && !projects.isEmpty();
	}

	public List<String> projects() {
		return projects;
	}

	/** {@code path} começa em {@code /organizations/{org}}; a query já vem codificada. */
	public JsonNode organization(String path) {
		return get("/organizations/" + encode(org) + path);
	}

	private JsonNode get(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/0" + path))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException exception) {
			throw new SentryUnavailable("falha de rede: " + exception.getClass().getSimpleName());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SentryUnavailable("interrompido");
		}
		if (response.statusCode() / 100 != 2) {
			throw new SentryUnavailable("HTTP " + response.statusCode() + " em " + path.substring(0, path.indexOf('?') < 0 ? path.length() : path.indexOf('?')));
		}
		try {
			return JSON.readTree(response.body());
		} catch (RuntimeException exception) {
			throw new SentryUnavailable("resposta ilegível");
		}
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Falha ao falar com o Sentry. A mensagem vai para o log, nunca com o token. */
	public static class SentryUnavailable extends RuntimeException {
		public SentryUnavailable(String message) {
			super(message);
		}
	}
}
