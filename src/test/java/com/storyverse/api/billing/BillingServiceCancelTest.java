package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthzService;

/** S5 da revisão de 09/11: excluir a conta não pode deixar assinatura sendo cobrada. */
class BillingServiceCancelTest {

	private final BillingRepository repository = mock(BillingRepository.class);
	private final AuthzService authz = mock(AuthzService.class);
	private final StripeGateway stripe = mock(StripeGateway.class);
	private final UUID user = UUID.randomUUID();
	private BillingService service;

	@BeforeEach
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void setUp() {
		JdbcClient jdbc = mock(JdbcClient.class);
		JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
		when(jdbc.sql(anyString())).thenReturn(spec);
		when(spec.param(anyString(), any())).thenReturn(spec);
		// DbSession.setUser só precisa não estourar: o RLS é do banco, não do teste.
		doReturn(mock(JdbcClient.MappedQuerySpec.class)).when(spec).query(any(RowMapper.class));
		service = new BillingService(repository, authz, jdbc, stripe, true, "price_x", "R$ 9,90", 7, 3, 30);
	}

	private void assinatura(String status) {
		when(repository.latestSubscription(user)).thenReturn(Optional.of(Map.of(
				"status", status, "stripe_subscription_id", "sub_1")));
	}

	@Test
	void assinaturaAtivaEhCanceladaNaExclusao() {
		assinatura("active");
		when(stripe.configured()).thenReturn(true);

		service.cancelForAccountDeletion(user);

		verify(stripe).cancelSubscription("sub_1");
	}

	@Test
	void trialEPastDueTambemSaoCancelados() {
		when(stripe.configured()).thenReturn(true);
		for (String status : new String[] { "trialing", "past_due" }) {
			assinatura(status);
			service.cancelForAccountDeletion(user);
		}
		verify(stripe, times(2)).cancelSubscription("sub_1");
	}

	@Test
	void assinaturaJaEncerradaNaoEhCanceladaDeNovo() {
		when(repository.latestSubscription(user)).thenReturn(Optional.of(Map.of(
				"status", "canceled", "stripe_subscription_id", "sub_1",
				"ended_at", "2026-11-01T00:00:00Z")));

		service.cancelForAccountDeletion(user);

		verify(stripe, never()).cancelSubscription(anyString());
	}

	@Test
	void contaSemAssinaturaNaoChamaOStripe() {
		when(repository.latestSubscription(user)).thenReturn(Optional.empty());

		service.cancelForAccountDeletion(user);

		verify(stripe, never()).cancelSubscription(anyString());
	}

	@Test
	void seOCancelamentoFalharAExclusaoAbortaComErroClaro() {
		assinatura("active");
		when(stripe.configured()).thenReturn(true);
		doThrow(new IllegalStateException("Stripe fora do ar")).when(stripe).cancelSubscription("sub_1");

		AuthException erro = assertThrows(AuthException.class, () -> service.cancelForAccountDeletion(user));

		assertEquals(502, erro.status());
		assertTrue(erro.getMessage().contains("conta não foi excluída"));
	}

	@Test
	void semStripeConfiguradoNaoExcluiComAssinaturaViva() {
		assinatura("active");
		when(stripe.configured()).thenReturn(false);

		AuthException erro = assertThrows(AuthException.class, () -> service.cancelForAccountDeletion(user));

		assertEquals(503, erro.status());
		verify(stripe, never()).cancelSubscription(anyString());
	}

	@Test
	void needsCancellationSoValeParaStatusQueAindaCobram() {
		assertTrue(BillingService.needsCancellation(Map.of("status", "active")));
		assertTrue(BillingService.needsCancellation(Map.of("status", "trialing")));
		assertTrue(BillingService.needsCancellation(Map.of("status", "past_due")));
		assertFalse(BillingService.needsCancellation(Map.of("status", "canceled")));
		assertFalse(BillingService.needsCancellation(Map.of("status", "unpaid")));
		assertFalse(BillingService.needsCancellation(null));
	}
}
