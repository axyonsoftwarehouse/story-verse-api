package com.storyverse.api.account;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.User;
import com.storyverse.api.auth.UserRepository;
import com.storyverse.api.billing.BillingService;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.common.SecurityEvents;
import com.storyverse.api.storage.StorageService;

/** S5: a exclusão da conta cancela a assinatura antes de apagar qualquer coisa. */
class AccountServiceTest {

	private final AuthService auth = mock(AuthService.class);
	private final UserRepository users = mock(UserRepository.class);
	private final JdbcClient jdbc = mock(JdbcClient.class);
	private final PasswordEncoder encoder = mock(PasswordEncoder.class);
	private final StorageService storage = mock(StorageService.class);
	private final RateLimitGate limits = mock(RateLimitGate.class);
	private final SecurityEvents security = mock(SecurityEvents.class);
	private final BillingService billing = mock(BillingService.class);
	private final UUID user = UUID.randomUUID();

	private AccountService service;

	@BeforeEach
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void setUp() {
		JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
		when(jdbc.sql(anyString())).thenReturn(spec);
		when(spec.param(anyString(), any())).thenReturn(spec);
		doReturn(mock(JdbcClient.MappedQuerySpec.class)).when(spec).query(any(RowMapper.class));

		when(auth.authenticate("token")).thenReturn(user);
		when(users.findById(user)).thenReturn(Optional.of(
				new User(user, "leitor@exemplo.test", "hash", Instant.now(), Map.of(), null, Instant.now())));
		when(encoder.matches("senha", "hash")).thenReturn(true);

		service = new AccountService(auth, users, jdbc, encoder, storage, limits, security, billing);
	}

	@Test
	void cancelaAAssinaturaAntesDeApagarOsArquivos() {
		service.delete("token", "senha");

		InOrder ordem = inOrder(billing, storage);
		ordem.verify(billing).cancelForAccountDeletion(user);
		ordem.verify(storage).deleteAllForUser(user);
	}

	@Test
	void seOCancelamentoFalharNadaEhApagado() {
		doThrow(new AuthException(502, "Não foi possível cancelar a assinatura; a conta não foi excluída."))
				.when(billing).cancelForAccountDeletion(user);

		assertThrows(AuthException.class, () -> service.delete("token", "senha"));

		verifyNoInteractions(storage);
		verify(jdbc, never()).sql(contains("delete_my_account"));
	}

	@Test
	void senhaErradaNaoChegaAAssinatura() {
		when(encoder.matches("errada", "hash")).thenReturn(false);

		assertThrows(AuthException.class, () -> service.delete("token", "errada"));

		verify(billing, never()).cancelForAccountDeletion(user);
		verifyNoInteractions(storage);
	}
}
