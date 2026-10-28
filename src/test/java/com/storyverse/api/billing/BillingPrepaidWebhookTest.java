package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.MailService;
import com.stripe.model.Charge;
import com.stripe.model.checkout.Session;

class BillingPrepaidWebhookTest {

	private final UUID conta = UUID.randomUUID();
	private BillingRepository repository;
	private MailService mail;
	private BillingWebhookService webhooks;

	@BeforeEach
	void setUp() {
		repository = mock(BillingRepository.class);
		mail = mock(MailService.class);
		webhooks = new BillingWebhookService(mock(StripeGateway.class), repository, mail, 30);
		when(repository.latestPrepaid(any())).thenReturn(Optional.empty());
		when(repository.latestSubscription(any())).thenReturn(Optional.empty());
		when(repository.insertPrepaid(any())).thenReturn(true);
	}

	private Session pago() {
		Session session = new Session();
		session.setId("cs_pix_1");
		session.setMode("payment");
		session.setPaymentStatus("paid");
		session.setClientReferenceId(conta.toString());
		session.setCustomer("cus_1");
		session.setPaymentIntent("pi_1");
		session.setAmountTotal(990L);
		session.setCurrency("brl");
		session.setMetadata(Map.of("kind", "prepaid", "days", "30"));
		return session;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> gravado() {
		ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
		verify(repository).insertPrepaid(row.capture());
		return row.getValue();
	}

	@Test
	void pixPagoAbreTrintaDiasEConfirmaPorEmail() {
		when(repository.email(conta)).thenReturn("leitora@example.com");
		Instant antes = Instant.now();

		webhooks.prepaid(pago());

		Map<String, Object> row = gravado();
		Instant inicio = ((Timestamp) row.get("starts_at")).toInstant();
		Instant fim = ((Timestamp) row.get("ends_at")).toInstant();
		assertTrue(!inicio.isBefore(antes));
		assertEquals(Duration.ofDays(30), Duration.between(inicio, fim));
		assertEquals("pi_1", row.get("payment_intent"));
		assertEquals(990, row.get("amount"));
		verify(repository).upsertInvoice(any());
		verify(mail).sendPrepaidConfirmation("leitora@example.com", fim);
	}

	@Test
	void mesmaSessaoDeNovoNaoSomaNemReenviaEmail() {
		when(repository.insertPrepaid(any())).thenReturn(false);

		webhooks.prepaid(pago());

		verify(repository, never()).upsertInvoice(any());
		verify(mail, never()).sendPrepaidConfirmation(anyString(), any());
	}

	@Test
	void pixAindaNaoPagoEsperaOAsync() {
		Session session = pago();
		session.setPaymentStatus("unpaid");

		webhooks.prepaid(session);

		verify(repository, never()).insertPrepaid(any());
	}

	@Test
	void pagarAntesDoFimComecaNoFimDoPeriodoAtual() {
		Instant fimAtual = Instant.now().plus(Duration.ofDays(12));
		when(repository.latestPrepaid(conta)).thenReturn(Optional.of(Map.of("ends_at", Timestamp.from(fimAtual))));

		webhooks.prepaid(pago());

		assertEquals(fimAtual, ((Timestamp) gravado().get("starts_at")).toInstant());
	}

	@Test
	void falhaNoEmailNaoDerrubaOWebhook() {
		when(repository.email(conta)).thenReturn("leitora@example.com");
		doThrow(new AuthException(503, "sem SMTP")).when(mail).sendPrepaidConfirmation(anyString(), any());

		assertDoesNotThrow(() -> webhooks.prepaid(pago()));
		verify(repository).upsertInvoice(any());
	}

	@Test
	void reembolsoMarcaOPeriodo() {
		Charge charge = new Charge();
		charge.setPaymentIntent("pi_1");

		webhooks.refund(charge);

		verify(repository).refundPrepaid(eq("pi_1"));
	}
}
