package com.storyverse.api.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma sanção de conta. Na lista do painel, {@code userName}/{@code emailMasked} vêm preenchidos;
 * no bloqueio de login eles podem ser nulos. O e-mail nunca sai completo daqui.
 */
public record Sanction(UUID id, UUID userId, String userName, String emailMasked, String kind,
		String reason, Instant expiresAt, UUID createdBy, String createdByName, Instant createdAt,
		UUID revokedBy, String revokedByName, Instant revokedAt) {
}
