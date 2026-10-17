package com.storyverse.api.ai;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.storyverse.api.auth.AuthException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As chaves de IA ficam **no servidor**. A API tenta os provedores em sequência (Groq → Gemini →
 * OpenRouter): cada modelo tem cota própria, então vários somam. O valor em claro nunca vai ao
 * navegador.
 */
@Service
public class AiService {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final long DEFAULT_COOLDOWN_MS = 60_000L;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final List<Provider> chain;
	private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

	private record Provider(String id, boolean gemini, String url, String apiKey, String model) {
	}

	public AiService(
			@Value("${GROQ_API_KEY:}") String groqKey,
			@Value("${GROQ_MODELS:openai/gpt-oss-120b,openai/gpt-oss-20b}") String groqModels,
			@Value("${GEMINI_API_KEY:}") String geminiKey,
			@Value("${GEMINI_MODEL:gemini-3.5-flash-lite,gemini-flash-lite-latest}") String geminiModels,
			@Value("${OPENROUTER_API_KEY:}") String openRouterKey,
			@Value("${OPENROUTER_MODELS:meta-llama/llama-3.3-70b-instruct:free}") String openRouterModels) {
		List<Provider> providers = new ArrayList<>();
		if (!groqKey.isBlank()) {
			for (String model : split(groqModels.isBlank() ? "openai/gpt-oss-120b,openai/gpt-oss-20b" : groqModels)) {
				providers.add(new Provider("groq:" + model, false,
						"https://api.groq.com/openai/v1/chat/completions", groqKey, model));
			}
		}
		if (!geminiKey.isBlank()) {
			for (String model : split(geminiModels.isBlank() ? "gemini-3.5-flash-lite,gemini-flash-lite-latest" : geminiModels)) {
				providers.add(new Provider("gemini:" + model, true,
						"https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", geminiKey, model));
			}
		}
		if (!openRouterKey.isBlank()) {
			for (String model : split(openRouterModels.isBlank() ? "meta-llama/llama-3.3-70b-instruct:free" : openRouterModels)) {
				providers.add(new Provider("openrouter:" + model, false,
						"https://openrouter.ai/api/v1/chat/completions", openRouterKey, model));
			}
		}
		this.chain = List.copyOf(providers);
	}

	public boolean live() {
		return !chain.isEmpty();
	}

	public int providers() {
		return chain.size();
	}

	public String reply(String system, List<AiMessage> messages, int maxTokens) {
		if (chain.isEmpty()) {
			throw new AuthException(503, "A inteligência artificial ainda não está configurada neste servidor.");
		}
		long now = System.currentTimeMillis();
		List<Provider> ready = chain.stream().filter(p -> cooldownUntil.getOrDefault(p.id(), 0L) <= now).toList();
		List<Provider> order = ready.isEmpty() ? chain : ready;
		long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
		for (Provider provider : order) {
			if (System.nanoTime() >= deadline) break;
			try {
				String text = provider.gemini()
						? gemini(provider, system, messages, maxTokens)
						: openAi(provider, system, messages, maxTokens);
				cooldownUntil.remove(provider.id());
				return text;
			} catch (AiHttpException exception) {
				if (exception.status == 429) {
					cooldownUntil.put(provider.id(), System.currentTimeMillis()
							+ (exception.retryAfterMs != null ? exception.retryAfterMs : DEFAULT_COOLDOWN_MS));
				}
			} catch (Exception ignored) {
				// Cota, modelo removido, chave inválida, rede: tenta o próximo.
			}
		}
		throw new AuthException(503, "Não foi possível gerar a resposta agora. Tente de novo em instantes.");
	}

	private String openAi(Provider provider, String system, List<AiMessage> messages, int maxTokens) throws Exception {
		List<Map<String, String>> msgs = new ArrayList<>();
		msgs.add(Map.of("role", "system", "content", system));
		for (AiMessage message : messages) {
			msgs.add(Map.of("role", message.role(), "content", message.content()));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", provider.model());
		body.put("messages", msgs);
		body.put("temperature", 0.85);
		int tokens = maxTokens;
		if (provider.model().contains("gpt-oss")) {
			body.put("reasoning_effort", "low");
			tokens += 200;
		}
		if (provider.model().contains("qwen3")) {
			body.put("reasoning_format", "hidden");
			tokens += 200;
		}
		body.put("max_tokens", tokens);

		HttpRequest request = HttpRequest.newBuilder(URI.create(provider.url()))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + provider.apiKey())
				.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new AiHttpException(response.statusCode(), retryAfter(response));
		}
		JsonNode text = MAPPER.readTree(response.body()).path("choices").path(0).path("message").path("content");
		if (!text.isString() || text.asText().isBlank()) {
			throw new IllegalStateException("resposta vazia");
		}
		return text.asText().trim();
	}

	private String gemini(Provider provider, String system, List<AiMessage> messages, int maxTokens) throws Exception {
		List<Map<String, Object>> contents = new ArrayList<>();
		for (AiMessage message : messages) {
			contents.add(Map.of(
					"role", "user".equals(message.role()) ? "user" : "model",
					"parts", List.of(Map.of("text", message.content()))));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
		body.put("contents", contents);
		body.put("generationConfig", Map.of("temperature", 0.9, "maxOutputTokens", maxTokens));

		String url = provider.url() + "?key=" + URLEncoder.encode(provider.apiKey(), StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new AiHttpException(response.statusCode(), retryAfter(response));
		}
		JsonNode parts = MAPPER.readTree(response.body()).path("candidates").path(0).path("content").path("parts");
		StringBuilder text = new StringBuilder();
		if (parts.isArray()) {
			for (int i = 0; i < parts.size(); i++) {
				JsonNode part = parts.get(i).path("text");
				if (part.isString()) {
					text.append(part.asText());
				}
			}
		}
		String result = text.toString().trim();
		if (result.isEmpty()) {
			throw new IllegalStateException("resposta vazia");
		}
		return result;
	}

	private static Long retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("retry-after")
				.map(raw -> {
					try {
						double seconds = Double.parseDouble(raw);
						return seconds > 0 ? (long) (seconds * 1000) : null;
					} catch (NumberFormatException ignored) {
						return null;
					}
				})
				.orElse(null);
	}

	private static List<String> split(String raw) {
		List<String> list = new ArrayList<>();
		for (String part : raw.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				list.add(trimmed);
			}
		}
		return list;
	}
}
