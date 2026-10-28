package com.storyverse.api.admin;

import java.time.Instant;

/** Corpo do 403 de conta suspensa/banida: o web mostra motivo e prazo. */
public record SanctionError(String error, String code, String kind, String reason, Instant expiresAt) {
}
