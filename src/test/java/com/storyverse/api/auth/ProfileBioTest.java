package com.storyverse.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProfileBioTest {

	@Test
	void trimsSpacesAndBlankLines() {
		assertEquals("Leitora de fantasia\n\nCafé e livros", ProfileBio.normalize("  Leitora   de\tfantasia \r\n\r\n\r\n\n  Café e livros  "));
		assertEquals("", ProfileBio.normalize("   \n  "));
	}

	@Test
	void removesInvisibleCharactersButKeepsComposedEmoji() {
		assertEquals("oi", ProfileBio.normalize("o\u0000i​"));
		String family = "👩‍👧";
		assertEquals(family, ProfileBio.normalize(family));
	}

	@Test
	void countsEmojiAsOneCharacter() {
		String emoji = "📚";
		assertEquals(emoji.repeat(160), ProfileBio.normalize(emoji.repeat(160)));
		assertEquals(400, assertThrows(AuthException.class, () -> ProfileBio.normalize(emoji.repeat(161))).status());
	}

	@Test
	void limitsLengthAndLines() {
		assertEquals(160, ProfileBio.normalize("a".repeat(160)).length());
		assertEquals(400, assertThrows(AuthException.class, () -> ProfileBio.normalize("a".repeat(161))).status());
		assertEquals(4, ProfileBio.normalize("a\nb\nc\nd").lines().count());
		assertEquals(400, assertThrows(AuthException.class, () -> ProfileBio.normalize("a\nb\nc\nd\ne")).status());
	}
}
