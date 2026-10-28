package com.storyverse.api.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;

/** Recurso ligado para todos pela configuração: não consulta o banco nem aceita concessão. */
class FeatureEveryoneTest {

	@Test
	void featureForEveryoneIsEnabledWithoutGrant() {
		FeatureAccessService service = new FeatureAccessService(null, null, " library , desconhecida");
		assertTrue(service.forEveryone("library"));
		assertFalse(service.forEveryone("desconhecida"));
		assertTrue(service.enabled(UUID.randomUUID(), "library"));
	}

	@Test
	void grantIsRejectedForFeatureOnForEveryone() {
		FeatureAccessService service = new FeatureAccessService(null, null, "library");
		assertEquals(409, assertThrows(AuthException.class,
				() -> service.set(UUID.randomUUID(), UUID.randomUUID(), "library", false)).status());
	}

	@Test
	void emptyConfigKeepsPerAccountControl() {
		assertFalse(new FeatureAccessService(null, null, "").forEveryone("library"));
	}
}
