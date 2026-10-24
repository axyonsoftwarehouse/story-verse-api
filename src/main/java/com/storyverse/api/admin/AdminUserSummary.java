package com.storyverse.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Uma conta na busca do painel. O e-mail vem sempre mascarado. */
public record AdminUserSummary(UUID id, String name, String emailMasked, boolean emailConfirmed,
		Instant createdAt, Instant lastSignInAt, List<String> roles) {
}
