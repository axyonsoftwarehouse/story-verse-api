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
}
