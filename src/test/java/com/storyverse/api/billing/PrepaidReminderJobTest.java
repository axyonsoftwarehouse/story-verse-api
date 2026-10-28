package com.storyverse.api.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.MailService;

class PrepaidReminderJobTest {

	@Test
	void avisaEMarcaSoQuemRecebeuOEmail() {
		BillingRepository repository = mock(BillingRepository.class);
		MailService mail = mock(MailService.class);
		UUID ok = UUID.randomUUID();
		UUID falhou = UUID.randomUUID();
		Instant fim = Instant.now().plusSeconds(86_400);
		when(repository.prepaidEndingSoon(any())).thenReturn(List.of(
				Map.of("id", falhou, "email", "a@example.com", "ends_at", Timestamp.from(fim)),
				Map.of("id", ok, "email", "b@example.com", "ends_at", Timestamp.from(fim))));
		doThrow(new AuthException(503, "sem SMTP")).when(mail).sendPrepaidEnding(eq("a@example.com"), any());

		new PrepaidReminderJob(repository, mail).remind();

		verify(mail).sendPrepaidEnding("b@example.com", fim);
		verify(repository).markPrepaidReminded(ok);
		verify(repository, never()).markPrepaidReminded(falhou);
	}
}
