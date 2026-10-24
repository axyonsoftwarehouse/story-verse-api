package com.storyverse.api.billing;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** Estado da assinatura e plano. Checkout/portal/webhook entram na etapa seguinte. */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

	private final BillingService billing;
	private final AuthService auth;

	public BillingController(BillingService billing, AuthService auth) {
		this.billing = billing;
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
}
