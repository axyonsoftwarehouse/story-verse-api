package com.storyverse.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TokenServiceTest {

	private final TokenService tokens = new TokenService();

	@Test
	void generatesUniqueUrlSafeTokens() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String token = tokens.newToken();
			assertThat(token).matches("[A-Za-z0-9_-]+");
			assertThat(seen.add(token)).isTrue();
		}
	}

	@Test
	void hashIsStableAndHexEncoded() {
		String hash = tokens.hash("exemplo-de-token");
		assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(tokens.hash("exemplo-de-token")).isEqualTo(hash);
		assertThat(tokens.hash("outro-token")).isNotEqualTo(hash);
	}
}
