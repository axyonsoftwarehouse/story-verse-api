package com.storyverse.api.forum;

import java.time.Instant;
import java.util.UUID;

import com.storyverse.api.auth.AuthException;

/** Cursor opaco e simples: epoch millis + id. */
record ForumCursor(Instant time, UUID id) {

	static ForumCursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		int separator = raw.indexOf(':');
		if (separator <= 0 || separator == raw.length() - 1) {
			throw new AuthException(400, "Cursor invalido.");
		}
		try {
			return new ForumCursor(
					Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))),
					UUID.fromString(raw.substring(separator + 1)));
		} catch (RuntimeException error) {
			throw new AuthException(400, "Cursor invalido.");
		}
	}

	static ForumCursor from(Instant time, UUID id) {
		return new ForumCursor(time, id);
	}

	String encode() {
		return time.toEpochMilli() + ":" + id;
	}
}
