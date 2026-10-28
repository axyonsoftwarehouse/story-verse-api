package com.storyverse.api.billing;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** Estado da assinatura, checkout, portal e webhook do Stripe. */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

	private final BillingService billing;
	private final BillingWebhookService webhooks;
	private final AuthService auth;

	public BillingController(BillingService billing, BillingWebhookService webhooks, AuthService auth) {
		this.billing = billing;
		this.webhooks = webhooks;
		this.auth = auth;
	}

	@GetMapping("/plans")
	public Map<String, Object> plans() {
		return billing.plans();
	}

	@GetMapping("/status")
	public Map<String, Object> status(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		return billing.status(user);
	}

	@PostMapping("/checkout")
	public Map<String, Object> checkout(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		return billing.checkout(user);
	}

	@PostMapping("/pix-checkout")
	public Map<String, Object> pixCheckout(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		return billing.pixCheckout(user);
	}

	@PostMapping("/portal")
	public Map<String, Object> portal(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID user = auth.authenticate(AuthTokens.bearer(authorization));
		return billing.portal(user);
	}

	/** Webhook público, autenticado pela assinatura `Stripe-Signature` (não pela sessão). */
	@PostMapping("/webhook")
	public ResponseEntity<Void> webhook(
			@RequestBody byte[] payload,
			@RequestHeader(value = "Stripe-Signature", required = false) String signature) {
		webhooks.handle(payload, signature);
		return ResponseEntity.ok().build();
	}
}
