package com.storyverse.api.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class AiCacheTest {

	@Test
	void keyIsStableAndChangesWithPromptVersionOrContent() {
		String first = AiCache.key("cast", "gb:123", "prompt-v1");
		assertEquals(first, AiCache.key("cast", "gb:123", "prompt-v1"));
		assertNotEquals(first, AiCache.key("cast", "gb:123", "prompt-v2"));
		assertNotEquals(first, AiCache.key("cast", "gb:124", "prompt-v1"));
		assertNotEquals(first, AiCache.key("hint", "gb:123", "prompt-v1"));
	}
}
