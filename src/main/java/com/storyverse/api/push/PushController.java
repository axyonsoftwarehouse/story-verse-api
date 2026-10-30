package com.storyverse.api.push;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Notificação no celular: chave pública (aberta) e inscrição dos aparelhos (com conta). */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {

	private final PushService push;

	public PushController(PushService push) {
		this.push = push;
	}

	@GetMapping("/key")
	public Map<String, Object> key() {
		return Map.of("publicKey", push.publicKey());
	}

	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void subscribe(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		push.subscribe(AuthTokens.bearer(authorization), body);
	}

	/** Manda um aviso de teste para os aparelhos da própria conta. */
	@PostMapping("/test")
	public Map<String, Object> test(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return push.test(AuthTokens.bearer(authorization));
	}

	@DeleteMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe(
			@RequestParam String endpoint,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		push.unsubscribe(AuthTokens.bearer(authorization), endpoint);
	}
}
