package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.stripe.param.checkout.SessionCreateParams.Mode;
import com.stripe.param.checkout.SessionCreateParams.SubscriptionData.TrialSettings.EndBehavior.MissingPaymentMethod;

class StripeGatewayTest {

	@Test
	void checkoutCancelaOTrialQueAcabaSemCartao() {
		var params = StripeGateway.checkoutParams("cus_x", "user-1", "price_x", "https://app.test", 7);
		var subscription = params.getSubscriptionData();
		assertEquals(7L, subscription.getTrialPeriodDays());
		assertEquals(MissingPaymentMethod.CANCEL,
				subscription.getTrialSettings().getEndBehavior().getMissingPaymentMethod());
	}

	@Test
	void pixAvulsoUsaSoAConfiguracaoDoPixEPagaTrintaDias() {
		var params = StripeGateway.pixCheckoutParams("cus_x", "user-1", 990L, 30, "pmc_pix", "https://app.test");
		assertEquals(Mode.PAYMENT, params.getMode());
		assertEquals("pmc_pix", params.getPaymentMethodConfiguration());
		assertEquals("prepaid", params.getMetadata().get("kind"));
		assertEquals("30", params.getMetadata().get("days"));
		assertEquals("user-1", params.getClientReferenceId());
		assertEquals(990L, params.getLineItems().get(0).getPriceData().getUnitAmount());
		assertEquals("brl", params.getLineItems().get(0).getPriceData().getCurrency());
		assertEquals(3600L, params.getPaymentMethodOptions().getPix().getExpiresAfterSeconds());
		assertEquals("https://app.test/conta/assinatura?ok=1", params.getSuccessUrl());
	}
}
