package com.storyverse.api.billing;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import com.stripe.param.checkout.SessionCreateParams.PaymentMethodCollection;

import jakarta.annotation.PostConstruct;

/**
 * Fronteira com o Stripe. Nenhum dado de cartão passa por aqui; só ids, URLs e estados.
 * Sem chave configurada, os métodos de rede respondem 503 e o gate fica desligado.
 */
@Service
public class StripeGateway {

	public record Checkout(String url, String sessionId) {
	}

	public record SubscriptionState(String id, String customerId, String priceId, String status,
			Long currentPeriodStart, Long currentPeriodEnd, boolean cancelAtPeriodEnd,
			Long trialEnd, Long canceledAt, Long amountCents, String currency) {
	}

	private final String secretKey;
	private final String webhookSecret;
	private final String priceId;
	private final String publicUrl;
	private final int trialDays;

	public StripeGateway(@Value("${app.billing.secret-key:}") String secretKey,
			@Value("${app.billing.webhook-secret:}") String webhookSecret,
			@Value("${app.billing.price-id:}") String priceId,
			@Value("${app.public-url:http://localhost:5173}") String publicUrl,
			@Value("${app.billing.trial-days:7}") int trialDays) {
		this.secretKey = secretKey == null ? "" : secretKey.trim();
		this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
		this.priceId = priceId == null ? "" : priceId.trim();
		this.publicUrl = publicUrl == null ? "http://localhost:5173" : publicUrl.replaceAll("/+$", "");
		this.trialDays = trialDays;
	}

	@PostConstruct
	void configure() {
		if (!secretKey.isBlank()) Stripe.apiKey = secretKey;
	}

	public boolean configured() {
		return !secretKey.isBlank() && !priceId.isBlank();
	}

	public String createCustomer(String email, String userId) {
		requireConfigured();
		try {
			CustomerCreateParams.Builder builder = CustomerCreateParams.builder();
			if (email != null && !email.isBlank()) builder.setEmail(email);
			builder.putMetadata("user_id", userId);
			Customer customer = Customer.create(builder.build());
			return customer.getId();
		} catch (StripeException exception) {
			throw new IllegalStateException("Não foi possível criar o cliente no Stripe.", exception);
		}
	}

	public Checkout checkout(String customerId, String userId) {
		requireConfigured();
		try {
			var params = com.stripe.param.checkout.SessionCreateParams.builder()
					.setMode(Mode.SUBSCRIPTION)
					.setCustomer(customerId)
					.setClientReferenceId(userId)
					.addLineItem(com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
							.setPrice(priceId).setQuantity(1L).build())
					.setSuccessUrl(publicUrl + "/conta/assinatura?ok=1")
					.setCancelUrl(publicUrl + "/assinar?cancelado=1")
					.setLocale(com.stripe.param.checkout.SessionCreateParams.Locale.PT_BR)
					.setPaymentMethodCollection(PaymentMethodCollection.IF_REQUIRED)
					.setAllowPromotionCodes(true)
					.setSubscriptionData(com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
							.setTrialPeriodDays((long) trialDays).build())
					.build();
			Session session = Session.create(params);
			return new Checkout(session.getUrl(), session.getId());
		} catch (StripeException exception) {
			throw new IllegalStateException("Não foi possível abrir o checkout.", exception);
		}
	}

	public String portal(String customerId) {
		requireConfigured();
		try {
			var params = SessionCreateParams.builder()
					.setCustomer(customerId)
					.setReturnUrl(publicUrl + "/conta/assinatura")
					.build();
			com.stripe.model.billingportal.Session session = com.stripe.model.billingportal.Session.create(params);
			return session.getUrl();
		} catch (StripeException exception) {
			throw new IllegalStateException("Não foi possível abrir o portal do cliente.", exception);
		}
	}

	public SubscriptionState subscription(String subscriptionId) {
		requireConfigured();
		try {
			Subscription subscription = Subscription.retrieve(subscriptionId);
			var items = subscription.getItems().getData();
			var price = items.isEmpty() ? null : items.get(0).getPrice();
			String priceId = price == null ? "" : price.getId();
			Long amount = price == null ? null : price.getUnitAmount();
			String currency = price == null ? null : price.getCurrency();
			Long periodStart = items.isEmpty() ? null : items.get(0).getCurrentPeriodStart();
			Long periodEnd = items.isEmpty() ? null : items.get(0).getCurrentPeriodEnd();
			return new SubscriptionState(subscription.getId(), subscription.getCustomer(), priceId,
					subscription.getStatus(), periodStart, periodEnd,
					Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()),
					subscription.getTrialEnd(), subscription.getCanceledAt(), amount, currency);
		} catch (StripeException exception) {
			throw new IllegalStateException("Não foi possível consultar a assinatura.", exception);
		}
	}

	/** Valida a assinatura Stripe e devolve o evento cru para o serviço de webhook. */
	public Event event(byte[] payload, String signature) {
		if (webhookSecret.isBlank()) {
			throw new IllegalStateException("Webhook do Stripe não configurado.");
		}
		try {
			return Webhook.constructEvent(new String(payload, StandardCharsets.UTF_8), signature, webhookSecret);
		} catch (SignatureVerificationException exception) {
			throw new IllegalArgumentException("Assinatura do webhook inválida.", exception);
		}
	}

	private void requireConfigured() {
		if (!configured()) {
			throw new IllegalStateException("Assinatura indisponível: Stripe não configurado.");
		}
	}
}
