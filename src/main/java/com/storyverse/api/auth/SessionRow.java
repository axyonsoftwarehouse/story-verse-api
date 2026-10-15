package com.storyverse.api.auth;

import java.time.Instant;
import java.util.UUID;

public record SessionRow(
		UUID id,
		UUID userId,
		Instant accessExpiresAt,
		Instant refreshExpiresAt,
		Instant revokedAt) {
}
