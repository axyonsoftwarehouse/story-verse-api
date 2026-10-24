package com.storyverse.api.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;

class BookKeyTest {

	private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

	@Test
	void aceitaLivroDoCatalogoComOrigemPropria() {
		BookKey key = BookKey.parse("cat:" + UUID);

		assertEquals("cat:" + UUID, key.value());
		assertEquals("catalog", key.source());
	}

	@Test
	void mantemAsChavesAntigas() {
		assertEquals("gutenberg", BookKey.parse("gb:1342").source());
		assertEquals("community", BookKey.parse("cm:" + UUID).source());
		assertEquals("local", BookKey.parse("local:-1729630000000123").source());
	}

	@Test
	void recusaChaveDesconhecida() {
		assertThrows(AuthException.class, () -> BookKey.parse("xx:" + UUID));
		assertThrows(AuthException.class, () -> BookKey.parse("cat:123"));
	}
}
