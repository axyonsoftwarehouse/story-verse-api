package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailMaskTest {

	@Test
	void masksLocalPartKeepingDomain() {
		assertEquals("we***@gmail.com", EmailMask.mask("wernersaboia@gmail.com"));
		assertEquals("a***@x.com", EmailMask.mask("ab@x.com"));
		assertEquals("a***@x.com", EmailMask.mask("a@x.com"));
		assertEquals("***", EmailMask.mask("semarroba"));
		assertEquals("***", EmailMask.mask(null));
	}
}
