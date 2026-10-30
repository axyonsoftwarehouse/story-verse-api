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

	@Test
	void presenceGoesOnlineOnceAndOfflineOnlyAfterTheGrace() {
		GroupStream stream = new GroupStream();
		List<String> events = new ArrayList<>();
		stream.onPresence((user, online) -> events.add(online ? "online" : "offline"));
		UUID user = UUID.randomUUID();

		SseEmitter first = stream.connect(user);
		SseEmitter second = stream.connect(user);
		assertEquals(List.of("online"), events, "segunda aba não repete o aviso");

		stream.leave(user, first);
		stream.leave(user, second);
		long left = System.currentTimeMillis();
		assertTrue(stream.online(user), "caiu agora: ainda conta como online");
		stream.flushOffline(left + GroupStream.OFFLINE_GRACE_MS / 2);
		assertEquals(List.of("online"), events);

		// Voltou dentro da tolerância: nada pisca.
		SseEmitter back = stream.connect(user);
		stream.flushOffline(left + GroupStream.OFFLINE_GRACE_MS * 2);
		assertEquals(List.of("online"), events);

		stream.leave(user, back);
		stream.flushOffline(System.currentTimeMillis() + GroupStream.OFFLINE_GRACE_MS + 1);
		assertEquals(List.of("online", "offline"), events);
		assertFalse(stream.online(user));
	}
}
