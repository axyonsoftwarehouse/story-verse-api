package com.storyverse.api.ai;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** IA com as chaves no servidor. O status é público; a geração exige sessão (protege a cota). */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

	private final AiService ai;
	private final AuthService auth;
	private final AiBudget budget;

	public AiController(AiService ai, AuthService auth, AiBudget budget) {
		this.ai = ai;
		this.auth = auth;
		this.budget = budget;
	}

	@GetMapping("/status")
	public Map<String, Object> status() {
		return Map.of("live", ai.live(), "providers", ai.providers());
	}

	@PostMapping("/reply")
	public Map<String, Object> reply(
			@RequestBody AiReplyRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		var user = auth.authenticate(AuthTokens.bearer(authorization));
		List<AiMessage> messages = request.messages() == null ? List.of() : request.messages();
		if (messages.size() > 20) {
			throw new com.storyverse.api.auth.AuthException(400, "Histórico grande demais.");
		}
		for (AiMessage message : messages)
			if (message == null || !("user".equals(message.role()) || "assistant".equals(message.role()))
					|| message.content() == null || message.content().length() > 4000)
				throw new com.storyverse.api.auth.AuthException(400, "Mensagem inválida ou grande demais.");
		String system = request.system() == null ? "" : request.system();
		if (system.length() > 6000) {
			system = system.substring(0, 6000);
		}
		int maxTokens = request.maxTokens() == null ? 250 : Math.min(Math.max(request.maxTokens(), 1), 800);
		if (!ai.live()) throw new com.storyverse.api.auth.AuthException(503, "IA indisponível.");
		budget.acquire();
		try {
			budget.reserve(user);
			return Map.of("text", ai.reply(system, messages, maxTokens));
		} finally { budget.release(); }
	}
}
