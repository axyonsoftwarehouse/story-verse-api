package com.storyverse.api.groups;

import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;

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
	/** O "digitando" vale por alguns segundos no cliente: um aviso por pessoa e grupo nesse intervalo basta. */
	static final long TYPING_INTERVAL_MS = 2_500;
	/**
	 * A conexão cai e volta sozinha (vida finita, troca de tela, rede): só avisa que a pessoa saiu
	 * se ela não voltar dentro deste tempo, para a bolinha de online não piscar.
	 */
	static final long OFFLINE_GRACE_MS = 20_000;

	private final Map<UUID, Deque<SseEmitter>> users = new ConcurrentHashMap<>();
	private final Map<String, Long> typing = new ConcurrentHashMap<>();
	/** Contas sem conexão aberta, desde quando (ainda dentro do tempo de tolerância). */
	private final Map<UUID, Long> leaving = new ConcurrentHashMap<>();
	private volatile BiConsumer<UUID, Boolean> presence = (user, online) -> {};

	/** Quem avisa os outros membros quando a conta entra ou sai (fora de qualquer trava daqui). */
	public void onPresence(BiConsumer<UUID, Boolean> listener) {
		presence = listener;
	}

	public SseEmitter connect(UUID userId) {
		boolean wasOffline = !online(userId);
		SseEmitter emitter = open(userId);
		if (wasOffline) presence.accept(userId, true);
		return emitter;
	}

	private synchronized SseEmitter open(UUID userId) {
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
		leaving.remove(userId);
		emitter.onCompletion(() -> leave(userId, emitter));
		emitter.onTimeout(() -> leave(userId, emitter));
		emitter.onError(ignored -> leave(userId, emitter));
		send(emitter, "ready", Map.of("ok", true));
		return emitter;
	}

	/** Com conexão aberta, ou que caiu há pouco e ainda pode voltar (tolerância). */
	public boolean online(UUID userId) {
		Deque<SseEmitter> emitters = users.get(userId);
		return (emitters != null && !emitters.isEmpty()) || leaving.containsKey(userId);
	}

	/** Avisa a saída de quem passou da tolerância sem voltar. */
	void flushOffline(long nowMs) {
		for (var entry : leaving.entrySet()) {
			if (nowMs - entry.getValue() < OFFLINE_GRACE_MS) continue;
			if (leaving.remove(entry.getKey(), entry.getValue())) presence.accept(entry.getKey(), false);
		}
	}

	/** Se este aviso de "digitando" deve sair (no máximo um a cada {@link #TYPING_INTERVAL_MS} por pessoa e grupo). */
	public boolean allowTyping(UUID userId, UUID groupId, long nowMs) {
		if (typing.size() > 10_000) typing.values().removeIf(at -> nowMs - at > TYPING_INTERVAL_MS);
		String key = userId + ":" + groupId;
		Long last = typing.get(key);
		if (last != null && nowMs - last < TYPING_INTERVAL_MS) return false;
		typing.put(key, nowMs);
		return true;
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

	synchronized void leave(UUID userId, SseEmitter emitter) {
		Deque<SseEmitter> emitters = users.get(userId);
		if (emitters == null) return;
		emitters.remove(emitter);
		if (emitters.isEmpty()) {
			users.remove(userId);
			leaving.put(userId, System.currentTimeMillis());
		}
	}

	private static void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data));
		} catch (Exception exception) {
			// Conexão morta: o onError/onCompletion limpa.
		}
	}

	/** Mantém as conexões vivas por trás do proxy (e revela conexões mortas). */
	@Scheduled(fixedRate = 10_000)
	public void presenceTick() {
		flushOffline(System.currentTimeMillis());
	}

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
