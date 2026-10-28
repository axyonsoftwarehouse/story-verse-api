package com.storyverse.api.admin;

import java.time.Instant;
import java.util.UUID;

import com.storyverse.api.auth.AuthException;

/** Cursor opaco e simples do painel: epoch millis + id (mesmo formato do fórum). */
public record AdminCursor(Instant time, UUID id) {

	public static AdminCursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		int separator = raw.indexOf(':');
		if (separator <= 0 || separator == raw.length() - 1) {
			throw new AuthException(400, "Cursor invalido.");
		}
		try {
			return new AdminCursor(
					Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
					UUID.fromString(raw.substring(separator + 1)));
		} catch (RuntimeException error) {
			throw new AuthException(400, "Cursor invalido.");
		}
	}

	public static AdminCursor from(Instant time, UUID id) {
		return new AdminCursor(time, id);
	}

	public String encode() {
		return time.toEpochMilli() + ":" + id;
	}
}
