package com.storyverse.api.groups;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.storyverse.api.auth.AuthException;

/**
 * Tempo real dos grupos: uma conexão SSE por conta (não uma por grupo), com os eventos de todos
 * os grupos dela. O banco é a fonte da verdade: o evento só sai depois do commit, e quem perdeu
 * algo (reconexão) busca as mensagens com {@code after}. Uma instância só da API: mapa em memória.
 */
@Component
public class GroupStream {

	static final int MAX_CONNECTIONS = 400;
	static final int MAX_PER_USER = 4;

	private final Map<UUID, Deque<SseEmitter>> users = new ConcurrentHashMap<>();

	public synchronized SseEmitter connect(UUID userId) {
		int total = users.values().stream().mapToInt(Deque::size).sum();
		if (total >= MAX_CONNECTIONS) throw new AuthException(429, "Limite de conexões atingido.");
		Deque<SseEmitter> mine = users.computeIfAbsent(userId, ignored -> new ConcurrentLinkedDeque<>());
		// Trocar de tela abre uma conexão nova antes de o servidor notar que a antiga caiu (só no
		// próximo ping): em vez de recusar a nova, fecha a mais antiga desta conta.
		while (mine.size() >= MAX_PER_USER) {
			SseEmitter oldest = mine.pollFirst();
			if (oldest != null) oldest.complete();
		}
		// Vida finita: o cliente reconecta e conexão morta não fica para sempre.
		SseEmitter emitter = new SseEmitter(300_000L);
		mine.addLast(emitter);
		emitter.onCompletion(() -> leave(userId, emitter));
		emitter.onTimeout(() -> leave(userId, emitter));
		emitter.onError(ignored -> leave(userId, emitter));
		send(emitter, "ready", Map.of("ok", true));
		return emitter;
	}

	public boolean online(UUID userId) {
		Deque<SseEmitter> emitters = users.get(userId);
		return emitters != null && !emitters.isEmpty();
	}

	/** Envia para cada conta da lista que está conectada, depois do commit da transação atual. */
	public void publish(Collection<UUID> recipients, String event, Object data) {
		Runnable deliver = () -> {
			for (UUID recipient : recipients) {
				Deque<SseEmitter> emitters = users.get(recipient);
				if (emitters == null) continue;
				for (SseEmitter emitter : emitters) {
					send(emitter, event, data);
				}
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					deliver.run();
				}
			});
		} else {
			deliver.run();
		}
	}

	private synchronized void leave(UUID userId, SseEmitter emitter) {
		Deque<SseEmitter> emitters = users.get(userId);
		if (emitters == null) return;
		emitters.remove(emitter);
		if (emitters.isEmpty()) users.remove(userId);
	}

	private static void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data));
		} catch (Exception exception) {
			// Conexão morta: o onError/onCompletion limpa.
		}
	}

	/** Mantém as conexões vivas por trás do proxy (e revela conexões mortas). */
	@Scheduled(fixedRate = 25_000)
	public void heartbeat() {
		for (Deque<SseEmitter> emitters : users.values()) {
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
