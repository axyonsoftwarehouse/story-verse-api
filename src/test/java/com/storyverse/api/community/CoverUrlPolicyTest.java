package com.storyverse.api.community;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;

class CoverUrlPolicyTest {

	private final CoverUrlPolicy policy = new CoverUrlPolicy(
			"https://api.storyverse.2.29.42.104.sslip.io",
			new String[]{"covers.openlibrary.org", "openlibrary.org", "archive.org"});

	@Test
	void acceptsOwnStorageOpenLibraryAndArchive() {
		assertDoesNotThrow(() -> policy.require("/api/v1/storage/public/profile-media/u/livros/a"));
		assertDoesNotThrow(() -> policy.require("/ol-covers/b/id/1-M.jpg"));
		assertDoesNotThrow(() -> policy.require("https://covers.openlibrary.org/b/id/1-M.jpg"));
		assertDoesNotThrow(() -> policy.require("https://api.storyverse.2.29.42.104.sslip.io/api/v1/storage/public/profile-media/u/livros/a"));
		assertNull(policy.require(null));
	}

	@Test
	void rejectsOtherHostsAndDangerousSchemes() {
		for (String value : new String[]{
				"https://evil.example/cover.jpg",
				"http://covers.openlibrary.org/b/id/1-M.jpg",
				"javascript:alert(1)",
				"data:image/svg+xml,<svg onload=alert(1)>",
				"https://user:pass@covers.openlibrary.org/b/id/1-M.jpg"}) {
			AuthException error = assertThrows(AuthException.class, () -> policy.require(value));
			assertEquals(400, error.status());
		}
	}
}
