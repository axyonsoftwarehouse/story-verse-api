package com.storyverse.api.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura da API do Umami auto-hospedado (rede interna da VPS) com um usuário só de visualização.
 * Faz login uma vez, guarda o token e refaz o login se ele expirar (401).
 */
@Component
public class UmamiApiClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private final String baseUrl;
	private final String username;
	private final String password;
	private final String websiteId;
	private volatile String token;

	public UmamiApiClient(
			@Value("${app.umami-api.url:}") String baseUrl,
			@Value("${app.umami-api.username:}") String username,
			@Value("${app.umami-api.password:}") String password,
			@Value("${app.umami-api.website-id:}") String websiteId) {
		String url = baseUrl == null ? "" : baseUrl.strip();
		this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		this.username = username == null ? "" : username.strip();
		this.password = password == null ? "" : password;
		this.websiteId = websiteId == null ? "" : websiteId.strip();
	}

	public boolean configured() {
		return !baseUrl.isEmpty() && !username.isEmpty() && !password.isEmpty() && !websiteId.isEmpty();
	}

	/** {@code path} começa em {@code /api/websites/{id}}; a query já vem codificada. */
	public JsonNode website(String path) {
		String url = baseUrl + "/api/websites/" + SentryApiClient.encode(websiteId) + path;
		String used = currentToken();
		HttpResponse<String> response = send(get(url, used));
		if (response.statusCode() == 401) {
			response = send(get(url, login(used)));
		}
		return body(response, path);
	}

	private String currentToken() {
		String current = token;
		return current == null ? login(null) : current;
	}

	/**
	 * Login com o token {@code stale} vencido (ou nenhum). As chamadas paralelas esperam aqui e
	 * reaproveitam o token que a primeira obteve, em vez de cada uma logar de novo.
	 */
	private synchronized String login(String stale) {
		String current = token;
		if (current != null && !current.equals(stale)) return current;
		String body;
		try {
			body = JSON.writeValueAsString(Map.of("username", username, "password", password));
		} catch (RuntimeException exception) {
			throw new UmamiUnavailable("login ilegível");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		JsonNode result = body(send(request), "/api/auth/login");
		JsonNode value = result.path("token");
		if (!value.isString() || value.asText().isBlank()) throw new UmamiUnavailable("login sem token");
		token = value.asText();
		return token;
	}

	private static HttpRequest get(String url, String bearer) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + bearer)
				.header("Accept", "application/json")
				.GET()
				.build();
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException exception) {
			throw new UmamiUnavailable("falha de rede: " + exception.getClass().getSimpleName());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new UmamiUnavailable("interrompido");
		}
	}

	private static JsonNode body(HttpResponse<String> response, String path) {
		if (response.statusCode() / 100 != 2) {
			int query = path.indexOf('?');
			throw new UmamiUnavailable("HTTP " + response.statusCode() + " em " + (query < 0 ? path : path.substring(0, query)));
		}
		try {
			return JSON.readTree(response.body());
		} catch (RuntimeException exception) {
			throw new UmamiUnavailable("resposta ilegível");
		}
	}

	/** Falha ao falar com o Umami. A mensagem vai para o log, nunca com a senha ou o token. */
	public static class UmamiUnavailable extends RuntimeException {
		public UmamiUnavailable(String message) {
			super(message);
		}
	}
}
