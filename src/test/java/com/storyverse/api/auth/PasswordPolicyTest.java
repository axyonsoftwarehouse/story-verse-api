package com.storyverse.api.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy();

	@Test
	void rejectsShortAndCommonPasswords() {
		AuthException shortPassword = assertThrows(AuthException.class,
				() -> policy.validate("curta", "a@example.test", null));
		assertEquals(400, shortPassword.status());

		AuthException commonPassword = assertThrows(AuthException.class,
				() -> policy.validate("password123", "a@example.test", null));
		assertEquals(400, commonPassword.status());
	}

	@Test
	void rejectsEmailAndNameInsideThePassword() {
		assertThrows(AuthException.class,
				() -> policy.validate("alice-2026", "alice@example.test", null));
		assertThrows(AuthException.class,
				() -> policy.validate("joao-2026", "a@example.test", "João"));
	}

	@Test
	void acceptsAReasonablePassword() {
		assertDoesNotThrow(() -> policy.validate("Tr0ub4dor&3", "a@example.test", "Leitor"));
	}
}
