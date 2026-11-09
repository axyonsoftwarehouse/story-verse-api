package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.MailService;

/** S5: evento de conta excluída é ignorado com 200, não vira 500 que o Stripe reenvia. */
class BillingWebhookServiceTest {

	private final StripeGateway gateway = mock(StripeGateway.class);
	private final BillingRepository repository = mock(BillingRepository.class);
	private final BillingWebhookService service =
			new BillingWebhookService(gateway, repository, mock(MailService.class), 30);

	@Test
	void clienteSemContaEhEventoIgnoradoEmVezDeErro() {
		when(gateway.subscription("sub_x")).thenReturn(state("sub_x", "cus_x"));
		when(repository.userByCustomer("cus_x")).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> service.sync("sub_x", null));

		verify(repository, never()).upsertSubscription(any());
	}

	@Test
	void clienteComContaAtualizaAAssinatura() {
		when(gateway.subscription("sub_x")).thenReturn(state("sub_x", "cus_x"));
		when(repository.userByCustomer("cus_x")).thenReturn(Optional.of(Map.of("user_id", UUID.randomUUID())));
		when(repository.upsertSubscription(any())).thenReturn(Optional.empty());

		service.sync("sub_x", null);

		verify(repository).upsertSubscription(any());
	}

	private static StripeGateway.SubscriptionState state(String id, String customer) {
		return new StripeGateway.SubscriptionState(id, customer, "price_x", "active",
				null, null, false, null, null, null, 990L, "brl");
	}
}
