package com.storyverse.api.forum;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.storyverse.api.auth.AuthException;

/**
 * Cursor opaco: epoch em microssegundos + id (+ ":1" quando a linha esta fixada).
 * Microssegundos porque o Postgres grava nessa precisao: cortar em milissegundos fazia a
 * ultima resposta de uma pagina voltar na seguinte.
 */
record ForumCursor(Instant time, UUID id, boolean pinned) {

	static ForumCursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String[] parts = raw.split(":");
		if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
			throw new AuthException(400, "Cursor invalido.");
		}
		try {
			long micros = Long.parseLong(parts[0]);
			Instant time = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
					Math.floorMod(micros, 1_000_000L) * 1_000L);
			return new ForumCursor(time, UUID.fromString(parts[1]), parts.length == 3 && "1".equals(parts[2]));
		} catch (RuntimeException error) {
			throw new AuthException(400, "Cursor invalido.");
		}
	}

	static ForumCursor from(Instant time, UUID id, boolean pinned) {
		return new ForumCursor(time, id, pinned);
	}

	/** O driver do Postgres nao sabe enviar {@link Instant}; {@link OffsetDateTime} vai como timestamptz. */
	OffsetDateTime timestamp() {
		return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
	}

	String encode() {
		long micros = Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000L), time.getNano() / 1_000L);
		return micros + ":" + id + (pinned ? ":1" : "");
	}
}
