package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BillingAccessFilterTest {

	@Test
	void isentaRotasPublicasEConta() {
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/collections"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books/cat:1"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/community/submissions"));
		assertTrue(BillingAccessFilter.exempt("POST", "/api/v1/billing/checkout"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/account"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/admin/contas"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/storage/public/profile-media/a"));
	}

	@Test
	void protegeOTextoEDemaisRotasDoProduto() {
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books/cat:1/text"));
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/me/library"));
		assertFalse(BillingAccessFilter.exempt("POST", "/api/v1/community/submissions"));
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/ai/status/extra"));
	}
}
