package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

class BillingGrantAdminTest {

	private final UUID admin = UUID.randomUUID();
	private final UUID conta = UUID.randomUUID();
	private BillingRepository repository;
	private AuthzService authz;
	private BillingAdminService service;

	@BeforeEach
	void setUp() {
		repository = mock(BillingRepository.class);
		AuthService auth = mock(AuthService.class);
		authz = mock(AuthzService.class);
		when(auth.authenticate("token")).thenReturn(admin);
		when(repository.email(conta)).thenReturn("familia@example.test");
		when(repository.activeGrant(conta)).thenReturn(Optional.empty());
		service = new BillingAdminService(repository, auth, authz, mock(JdbcClient.class, RETURNS_DEEP_STUBS));
	}

	private int status(Runnable call) {
		return assertThrows(AuthException.class, call::run).status();
	}

	@Test
	void concedeSemValidadeERegistraNaAuditoria() {
		when(repository.createGrant(conta, "Família", null, admin)).thenReturn(Map.of("id", "g1"));

		assertEquals(Map.of("id", "g1"), service.grant("token", conta, "  Família ", null));

		verify(authz).audit(eq(admin), eq("billing.grant"), eq("user"), eq(conta.toString()), any());
	}

	@Test
	void concedeComValidadeFutura() {
		Instant ate = Instant.now().plusSeconds(30 * 86_400L);
		service.grant("token", conta, "Testador", ate.toString());
		verify(repository).createGrant(conta, "Testador", ate, admin);
	}

	@Test
	void recusaMotivoVazioOuLongo() {
		assertEquals(400, status(() -> service.grant("token", conta, "   ", null)));
		assertEquals(400, status(() -> service.grant("token", conta, "x".repeat(201), null)));
		verify(repository, never()).createGrant(any(), anyString(), any(), any());
	}

	@Test
	void recusaValidadeNoPassadoOuInvalida() {
		assertEquals(400, status(() -> service.grant("token", conta, "Teste", Instant.now().minusSeconds(60).toString())));
		assertEquals(400, status(() -> service.grant("token", conta, "Teste", "amanhã")));
	}

	@Test
	void contaInexistenteDa404() {
		UUID outra = UUID.randomUUID();
		assertEquals(404, status(() -> service.grant("token", outra, "Teste", null)));
	}

	@Test
	void naoDuplicaAcessoAtivo() {
		when(repository.activeGrant(conta)).thenReturn(Optional.of(Map.of("id", "g0")));
		assertEquals(409, status(() -> service.grant("token", conta, "Família", null)));
		verify(repository, never()).createGrant(any(), anyString(), isNull(), any());
	}

	@Test
	void soSuperadminConcede() {
		doThrow(new AuthException(403, "Sem permissão.")).when(authz).requireRole(admin, AuthzService.SUPERADMIN);
		assertEquals(403, status(() -> service.grant("token", conta, "Família", null)));
		assertEquals(403, status(() -> service.revokeGrant("token", UUID.randomUUID())));
		verify(repository, never()).createGrant(any(), anyString(), any(), any());
	}

	@Test
	void retirarAcessoJaRetiradoDa404() {
		UUID grant = UUID.randomUUID();
		when(repository.revokeGrant(grant, admin)).thenReturn(0);
		assertEquals(404, status(() -> service.revokeGrant("token", grant)));

		when(repository.revokeGrant(grant, admin)).thenReturn(1);
		service.revokeGrant("token", grant);
		verify(authz).audit(eq(admin), eq("billing.grant.revoke"), eq("billing_grant"), eq(grant.toString()), any());
	}
}
