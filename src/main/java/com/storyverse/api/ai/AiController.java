package com.storyverse.api.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** IA com as chaves no servidor. O status é público; a geração exige sessão e cota. */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

	private static final int MAX_HISTORY = 10;
	private static final int MAX_MESSAGE_CHARS = 4000;
	private static final int MAX_SYSTEM_CHARS = 6000;
	private static final int MAX_OUTPUT_TOKENS = 300;

	private final AiService ai;
	private final AuthService auth;
	private final AiBudget budget;
	private final AiMetrics metrics;

	public AiController(AiService ai, AuthService auth, AiBudget budget, AiMetrics metrics) {
		this.ai = ai;
		this.auth = auth;
		this.budget = budget;
		this.metrics = metrics;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("live", ai.live(), "providers", ai.providers());
	}

	/** Uso e restante do dia, direto do servidor. */
	@GetMapping("/quota")
	public AiQuota quota(@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		return budget.quota(user);
	}

	@PostMapping("/reply")
	public Map<String, Object> reply(
			@RequestBody AiReplyRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		List<AiMessage> messages = request.messages() == null ? List.of() : request.messages();
		if (messages.size() > MAX_HISTORY) {
			throw new AuthException(400, "Histórico grande demais.");
		}
		for (AiMessage message : messages) {
			if (message == null || !("user".equals(message.role()) || "assistant".equals(message.role()))
					|| message.content() == null || message.content().length() > MAX_MESSAGE_CHARS) {
				throw new AuthException(400, "Mensagem inválida ou grande demais.");
			}
		}
		String system = request.system() == null ? "" : request.system();
		if (system.length() > MAX_SYSTEM_CHARS) {
			system = system.substring(0, MAX_SYSTEM_CHARS);
		}
		int maxTokens = request.maxTokens() == null ? 250
				: Math.min(Math.max(request.maxTokens(), 1), MAX_OUTPUT_TOKENS);
		if (!ai.live()) {
			throw new AuthException(503, "IA indisponível.", "AI_UNAVAILABLE");
		}

		budget.acquire();
		try {
			AiQuota quota;
			try {
				quota = budget.reserve(user);
			} catch (AuthException exception) {
				metrics.record(user, "chat", null, null, "budget_blocked", 0, 0, 0, false,
						exception.code());
				throw exception;
			}

			long started = System.nanoTime();
			try {
				AiService.AiResult result = ai.reply(system, messages, maxTokens);
				metrics.record(user, "chat", result.provider(), result.model(), "ok",
						result.latencyMs(), result.tokensIn(), result.tokensOut(), false, null);
				return Map.of(
						"text", result.text(),
						"remaining", quota.remaining(),
						"limit", quota.limit());
			} catch (RuntimeException exception) {
				long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
				String code = exception instanceof AuthException authError ? authError.code() : null;
				metrics.record(user, "chat", null, null, "error", latencyMs, 0, 0, false, code);
				throw exception;
			}
		} finally {
			budget.release();
		}
	}
}
