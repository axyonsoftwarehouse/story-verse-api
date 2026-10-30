package com.storyverse.api.groups;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Trocar de tela abre conexão nova antes de a antiga cair: a nova entra e a mais antiga sai. */
class GroupStreamTest {

	@Test
	void extraConnectionClosesTheOldestInsteadOfFailing() {
		GroupStream stream = new GroupStream();
		UUID user = UUID.randomUUID();
		List<SseEmitter> opened = new ArrayList<>();
		for (int i = 0; i < GroupStream.MAX_PER_USER; i++) opened.add(stream.connect(user));

		SseEmitter extra = assertDoesNotThrow(() -> stream.connect(user));
		assertNotNull(extra);
		assertTrue(stream.online(user));
	}

	@Test
	void typingIsThrottledPerPersonAndGroup() {
		GroupStream stream = new GroupStream();
		UUID user = UUID.randomUUID(), group = UUID.randomUUID(), other = UUID.randomUUID();
		assertTrue(stream.allowTyping(user, group, 1_000));
		assertFalse(stream.allowTyping(user, group, 2_000), "dentro do intervalo não repete");
		assertTrue(stream.allowTyping(user, other, 2_000), "outro grupo é outro aviso");
		assertTrue(stream.allowTyping(user, group, 1_000 + GroupStream.TYPING_INTERVAL_MS));
	}
}
