package com.storyverse.api.presence;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Quantas pessoas estão em cada sala agora (tempo real). Cada cliente mantém uma conexão SSE com
 * uma "key" (uma conta conta uma vez, mesmo com várias abas; visitante conta por aba). Só o número
 * circula: ninguém vê quem são.
 */
@Service
public class PresenceService {

	private final Map<String, Map<String, Set<SseEmitter>>> rooms = new ConcurrentHashMap<>();

	public synchronized SseEmitter join(String room, String key) {
		// Finite lifetime; EventSource reconnects and stale connections cannot live forever.
		int connections = rooms.values().stream().flatMap(m -> m.values().stream()).mapToInt(Set::size).sum();
		if (connections >= 500 || rooms.size() >= 200 && !rooms.containsKey(room))
			throw new com.storyverse.api.auth.AuthException(429, "Limite de conexões atingido.");
		var existing = rooms.get(room);
		if (existing != null && existing.getOrDefault(key, Set.of()).size() >= 5)
			throw new com.storyverse.api.auth.AuthException(429, "Muitas conexões para este leitor.");
		SseEmitter emitter = new SseEmitter(300_000L);
		rooms.computeIfAbsent(room, ignored -> new ConcurrentHashMap<>())
				.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
				.add(emitter);
		emitter.onCompletion(() -> leave(room, key, emitter));
		emitter.onTimeout(() -> leave(room, key, emitter));
		emitter.onError(ignored -> leave(room, key, emitter));
		broadcast(room);
		return emitter;
	}

	public long count(String room) {
		Map<String, Set<SseEmitter>> keys = rooms.get(room);
		return keys == null ? 0 : keys.size();
	}

	private synchronized void leave(String room, String key, SseEmitter emitter) {
		Map<String, Set<SseEmitter>> keys = rooms.get(room);
		if (keys == null) {
			return;
		}
		Set<SseEmitter> emitters = keys.get(key);
		if (emitters != null) {
			emitters.remove(emitter);
			if (emitters.isEmpty()) {
				keys.remove(key);
			}
		}
		if (keys.isEmpty()) {
			rooms.remove(room);
		}
		broadcast(room);
	}

	private void broadcast(String room) {
		Map<String, Set<SseEmitter>> keys = rooms.get(room);
		if (keys == null) {
			return;
		}
		String data = "{\"count\":" + keys.size() + "}";
		for (Set<SseEmitter> emitters : keys.values()) {
			for (SseEmitter emitter : emitters) {
				try {
					emitter.send(SseEmitter.event().name("count").data(data));
				} catch (Exception exception) {
					// Conexão morta: o onError/onCompletion limpa.
				}
			}
		}
	}

	/** Mantém as conexões vivas por trás do proxy (e revela conexões mortas). */
	@Scheduled(fixedRate = 25_000)
	public void heartbeat() {
		for (Map<String, Set<SseEmitter>> keys : rooms.values()) {
			for (Set<SseEmitter> emitters : keys.values()) {
				for (SseEmitter emitter : emitters) {
					try {
						emitter.send(SseEmitter.event().comment("ping"));
					} catch (Exception exception) {
						// Limpo pelo onError.
					}
				}
			}
		}
	}
}
