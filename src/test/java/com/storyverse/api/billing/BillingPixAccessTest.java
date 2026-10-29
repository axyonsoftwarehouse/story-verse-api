package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthzService;

class BillingPixAccessTest {

	private final UUID conta = UUID.randomUUID();
	private BillingRepository repository;
	private StripeGateway stripe;
	private BillingService billing;

	@BeforeEach
	void setUp() {
		repository = mock(BillingRepository.class);
		stripe = mock(StripeGateway.class);
		billing = new BillingService(repository, mock(AuthzService.class), mock(JdbcClient.class, RETURNS_DEEP_STUBS),
				stripe, true, "price_x", "R$ 9,90", 7, 3, 30);
		when(repository.activeGrant(conta)).thenReturn(Optional.empty());
		when(repository.activePrepaid(conta)).thenReturn(Optional.empty());
		when(repository.latestSubscription(conta)).thenReturn(Optional.empty());
		when(stripe.configured()).thenReturn(true);
		when(stripe.pixConfigured()).thenReturn(true);
	}

	@Test
	void periodoPixLiberaAteOFimDaSequencia() {
		Instant ate = Instant.parse("2026-12-27T12:00:00Z");
		when(repository.activePrepaid(conta)).thenReturn(Optional.of(Map.of("until", Timestamp.from(ate))));

		BillingService.Access acesso = billing.access(conta);

		assertTrue(acesso.active());
		assertEquals("pix", acesso.reason());
		assertEquals(ate.toString(), acesso.accessUntil());
	}

	@Test
	void capabilitiesDizOMotivoDoAcesso() {
		Instant ate = Instant.parse("2026-12-27T12:00:00Z");
		when(repository.activePrepaid(conta)).thenReturn(Optional.of(Map.of("until", Timestamp.from(ate))));

		Map<String, Object> caps = billing.capabilities(conta);

		assertEquals("none", caps.get("status"));
		assertEquals("pix", caps.get("reason"));
		assertEquals(ate.toString(), caps.get("accessUntil"));
	}

	@Test
	void cartaoCobrandoRecusaPix() {
		when(repository.latestSubscription(conta)).thenReturn(Optional.of(Map.of("status", "active")));

		AuthException erro = assertThrows(AuthException.class, () -> billing.pixCheckout(conta));

		assertEquals(409, erro.status());
		verify(stripe, never()).pixCheckout(any(), any());
	}

	@Test
	void semConfiguracaoDoPixResponde503() {
		when(stripe.pixConfigured()).thenReturn(false);

		assertEquals(503, assertThrows(AuthException.class, () -> billing.pixCheckout(conta)).status());
	}

	@Test
	void abreOCheckoutDoPixComOClienteExistente() {
		when(repository.customer(conta)).thenReturn(Optional.of(Map.of("stripe_customer_id", "cus_1")));
		when(stripe.pixCheckout("cus_1", conta.toString())).thenReturn(new StripeGateway.Checkout("https://pix", "cs_1"));

		assertEquals(Map.of("url", "https://pix"), billing.pixCheckout(conta));
	}
}
