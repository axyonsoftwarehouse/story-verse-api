package com.storyverse.api.forum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;

class ForumCursorTest {

	@Test
	void keepsMicrosecondsLikePostgres() {
		Instant time = Instant.parse("2026-10-20T03:25:29.095443Z");
		UUID id = UUID.randomUUID();
		ForumCursor decoded = ForumCursor.decode(ForumCursor.from(time, id, false).encode());
		assertEquals(time, decoded.time());
		assertEquals(id, decoded.id());
		assertFalse(decoded.pinned());
	}

	@Test
	void carriesPinnedFlag() {
		Instant time = Instant.parse("2026-10-20T03:25:29.000001Z");
		UUID id = UUID.randomUUID();
		String raw = ForumCursor.from(time, id, true).encode();
		assertTrue(raw.endsWith(":1"));
		assertTrue(ForumCursor.decode(raw).pinned());
	}

	@Test
	void bindsAsOffsetDateTime() {
		Instant time = Instant.parse("2026-10-20T03:25:29.095443Z");
		OffsetDateTime bound = ForumCursor.from(time, UUID.randomUUID(), false).timestamp();
		assertEquals(ZoneOffset.UTC, bound.getOffset());
		assertEquals(time, bound.toInstant());
	}

	@Test
	void rejectsGarbage() {
		assertNull(ForumCursor.decode(null));
		assertNull(ForumCursor.decode(" "));
		for (String raw : new String[] { "abc", "1:", ":" + UUID.randomUUID(), "x:" + UUID.randomUUID(),
				"1:nao-e-uuid", "1:" + UUID.randomUUID() + ":1:2" }) {
			assertEquals(400, assertThrows(AuthException.class, () -> ForumCursor.decode(raw)).status(), raw);
		}
	}

	@Test
	void searchTextIsLiteral() {
		assertEquals("100\\% certo", ForumRepository.likeLiteral(" 100% certo "));
		assertEquals("a\\_b\\\\c", ForumRepository.likeLiteral("a_b\\c"));
		assertEquals(100, ForumRepository.likeLiteral("x".repeat(300)).length());
	}
}
