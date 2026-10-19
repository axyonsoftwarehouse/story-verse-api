package com.storyverse.api.auth;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record User(
		UUID id,
		String email,
		String encryptedPassword,
		Instant emailConfirmedAt,
		Map<String, Object> metadata,
		Instant lastSignInAt,
		Instant createdAt) {
}
