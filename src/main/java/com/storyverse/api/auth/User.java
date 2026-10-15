package com.storyverse.api.auth;

import java.time.Instant;
import java.util.UUID;

public record User(
		UUID id,
		String email,
		String encryptedPassword,
		String name,
		Instant lastSignInAt,
		Instant createdAt) {
}
