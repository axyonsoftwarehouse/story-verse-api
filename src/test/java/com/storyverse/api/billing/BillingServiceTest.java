package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BillingServiceTest {

	@Test
	void activeValeAteOFimDoPeriodo() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "active",
				"current_period_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "active",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void trialValeAteOTrialEnd() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "trialing",
				"trial_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "trialing",
				"trial_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void pastDueTemCarenciaDeTresDias() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "past_due",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "past_due",
				"current_period_end", Instant.now().minusSeconds(4 * 86_400L).toString()), 3).active());
	}

	@Test
	void canceledValeAteOFimDoPeriodo() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "canceled",
				"current_period_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "canceled",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void unpaidEIncompleteNaoLiberam() {
		assertFalse(BillingService.evaluate(Map.of("status", "unpaid"), 3).active());
		assertFalse(BillingService.evaluate(Map.of("status", "incomplete"), 3).active());
		assertFalse(BillingService.evaluate(Map.of("status", "paused"), 3).active());
	}
}
