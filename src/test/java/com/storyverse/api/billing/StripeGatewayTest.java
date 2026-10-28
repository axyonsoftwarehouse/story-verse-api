package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
