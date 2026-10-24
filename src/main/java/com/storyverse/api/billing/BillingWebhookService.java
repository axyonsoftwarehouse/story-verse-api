package com.storyverse.api.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;

/** Processa o webhook do Stripe de forma idempotente; busca sempre o estado atual da assinatura. */
@Service
public class BillingWebhookService {

	private final StripeGateway gateway;
	private final BillingRepository repository;

	public BillingWebhookService(StripeGateway gateway, BillingRepository repository) {
		this.gateway = gateway;
		this.repository = repository;
	}

	@Transactional
	public void handle(byte[] payload, String signature) {
		if (signature == null || signature.isBlank()) {
			throw new AuthException(400, "Assinatura do webhook ausente.");
		}
		Event event;
		try {
			event = gateway.event(payload, signature);
		} catch (IllegalArgumentException exception) {
			throw new AuthException(400, "Assinatura do webhook inválida.");
		}
		repository.beginWrite();
		if (repository.eventProcessed(event.getId())) return;
		repository.beginEvent(event.getId(), event.getType());
		try {
			process(event);
			repository.finishEvent(event.getId(), null);
		} catch (RuntimeException exception) {
			repository.finishEvent(event.getId(),
					exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
			throw new AuthException(500, "Falha ao processar o webhook.");
		}
	}

	private void process(Event event) {
		StripeObject object = object(event);
		switch (event.getType()) {
			case "checkout.session.completed" -> checkout(object);
			case "customer.subscription.created", "customer.subscription.updated",
					"customer.subscription.deleted", "customer.subscription.trial_will_end" ->
				subscription(object, null);
			case "invoice.paid", "invoice.payment_failed" -> invoice(object);
			// Reembolso/disputa entram no financeiro e não mudam o acesso sozinhos.
			case "charge.refunded", "charge.dispute.created" -> {
				// registrado em billing_events
			}
			default -> {
				// Evento não usado nesta fase; fica registrado para auditoria.
			}
		}
	}

	private void checkout(StripeObject object) {
		if (!(object instanceof Session session)) throw new AuthException(500, "Checkout inválido.");
		String customerId = session.getCustomer();
		String rawUser = session.getClientReferenceId();
		if (rawUser == null && session.getMetadata() != null) rawUser = session.getMetadata().get("user_id");
		if (rawUser == null || customerId == null) throw new AuthException(500, "Checkout sem conta ou cliente.");
		UUID userId;
		try {
			userId = UUID.fromString(rawUser);
		} catch (RuntimeException exception) {
			throw new AuthException(500, "Checkout com conta inválida.");
		}
		repository.upsertCustomer(userId, customerId);
		if (session.getSubscription() != null) subscription(session.getSubscription(), null);
	}

	private void invoice(StripeObject object) {
		if (!(object instanceof Invoice invoice)) throw new AuthException(500, "Fatura inválida.");
		String subscriptionId = invoice.getParent() == null || invoice.getParent().getSubscriptionDetails() == null
				? null
				: invoice.getParent().getSubscriptionDetails().getSubscription();
		if (subscriptionId == null) return;
		subscription(subscriptionId, invoice.getStatus());
	}

	private void subscription(StripeObject object, String invoiceStatus) {
		if (object instanceof Subscription fromEvent) {
			sync(fromEvent.getId(), invoiceStatus);
			return;
		}
		throw new AuthException(500, "Assinatura inválida.");
	}

	private void subscription(String subscriptionId, String invoiceStatus) {
		sync(subscriptionId, invoiceStatus);
	}

	private void sync(String subscriptionId, String invoiceStatus) {
		StripeGateway.SubscriptionState state = gateway.subscription(subscriptionId);
		Map<String, Object> link = repository.userByCustomer(state.customerId())
				.orElseThrow(() -> new AuthException(500, "Cliente do Stripe sem conta vinculada."));
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("user_id", link.get("user_id"));
		params.put("stripe_subscription_id", state.id());
		params.put("stripe_price_id", state.priceId());
		params.put("status", state.status());
		params.put("current_period_start", timestamp(state.currentPeriodStart()));
		params.put("current_period_end", timestamp(state.currentPeriodEnd()));
		params.put("cancel_at_period_end", state.cancelAtPeriodEnd());
		params.put("trial_end", timestamp(state.trialEnd()));
		params.put("canceled_at", timestamp(state.canceledAt()));
		params.put("latest_invoice_status", invoiceStatus);
		params.put("amount_cents", state.amountCents());
		params.put("currency", state.currency());
		repository.upsertSubscription(params);
	}

	private static StripeObject object(Event event) {
		StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
		if (object != null) return object;
		try {
			return event.getDataObjectDeserializer().deserializeUnsafe();
		} catch (com.stripe.exception.EventDataObjectDeserializationException exception) {
			throw new AuthException(500, "Evento do Stripe sem objeto legível.");
		}
	}

	private static Timestamp timestamp(Long seconds) {
		return seconds == null ? null : Timestamp.from(Instant.ofEpochSecond(seconds));
	}
}
