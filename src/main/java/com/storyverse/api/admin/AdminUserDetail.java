package com.storyverse.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Detalhe de uma conta no painel. {@code recentAudit} é nulo para quem não é superadmin. */
public record AdminUserDetail(UUID id, String name, String email, boolean emailConfirmed, Instant createdAt,
		Instant lastSignInAt, List<String> roles, List<String> features, int activeSessions,
		List<Map<String, Object>> recentAudit, List<Sanction> sanctions) {
}
