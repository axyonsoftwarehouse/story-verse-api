package com.storyverse.api.billing;

import java.time.Instant;
import java.util.Map;

/**
 * Regras do Pix pré-pago (30 dias por pagamento). A conta Stripe do Brasil não tem Pix
 * Automático nem Pix em fatura, então o Pix é avulso e cada pagamento abre um período.
 */
final class PrepaidRules {

	private PrepaidRules() {
	}

	/**
	 * Início do período novo: nunca antes de agora, do fim do último Pix, do fim do teste grátis
	 * nem do fim do cartão já cancelado. Pagar antes do fim soma dias em vez de perdê-los.
	 */
	static Instant startAt(Instant now, Instant lastPrepaidEnd, Map<String, Object> subscription) {
		Instant start = now;
		if (lastPrepaidEnd != null && lastPrepaidEnd.isAfter(start)) start = lastPrepaidEnd;
		if (subscription != null) {
			String status = String.valueOf(subscription.get("status"));
			Instant cardEnd = null;
			if ("trialing".equals(status)) {
				cardEnd = BillingService.instant(subscription.get("trial_end"));
			} else if (("active".equals(status) || "past_due".equals(status))
					&& Boolean.TRUE.equals(subscription.get("cancel_at_period_end"))) {
				cardEnd = BillingService.instant(subscription.get("current_period_end"));
			}
			if (cardEnd != null && cardEnd.isAfter(start)) start = cardEnd;
		}
		return start;
	}

	/** Cartão cobrando sozinho: pagar Pix por cima seria pagar em dobro. */
	static boolean blocksPix(Map<String, Object> subscription) {
		if (subscription == null) return false;
		String status = String.valueOf(subscription.get("status"));
		return ("active".equals(status) || "past_due".equals(status))
				&& !Boolean.TRUE.equals(subscription.get("cancel_at_period_end"));
	}
}
