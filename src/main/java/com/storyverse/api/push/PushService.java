package com.storyverse.api.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;

/**
 * Notificação no celular (Web Push) sem chave para configurar: o par VAPID é gerado aqui na
 * primeira vez e fica no banco.
 */
@Service
public class PushService {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);

	/** Até quantos aparelhos por conta (o mais antigo sai). */
	static final int MAX_DEVICES = 10;
	/** Um aviso por pessoa e grupo neste intervalo: a notificação seguinte substitui a anterior. */
	static final long THROTTLE_MS = 20_000;
	/**
	 * Só os serviços de push dos navegadores: o servidor faz a chamada para o endereço que o
	 * navegador mandou, então qualquer outro endereço (rede interna, por exemplo) é recusado.
	 */
	private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "android.googleapis.com",
			"updates.push.services.mozilla.com", "web.push.apple.com");
	private static final Set<String> HOST_SUFFIXES = Set.of(".push.services.mozilla.com", ".notify.windows.com", ".push.apple.com");

	private final JdbcClient jdbc;
	private final AuthService auth;
	private final RateLimitGate limits;
	private final String subject;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
	private volatile Keys keys;

	record Keys(byte[] publicRaw, PrivateKey privateKey) {
	}

	record Target(UUID userId, String endpoint, String p256dh, String auth) {
	}

	public PushService(JdbcClient jdbc, AuthService auth, RateLimitGate limits,
			@Value("${app.public-url:https://storyverse.2.29.42.104.sslip.io}") String publicUrl) {
		this.jdbc = jdbc;
		this.auth = auth;
		this.limits = limits;
		this.subject = publicUrl.trim().replaceAll("/+$", "");
	}

	/** Chave pública do VAPID, que o navegador usa para se inscrever. */
	public String publicKey() {
		return WebPushCrypto.b64(keys().publicRaw());
	}

	synchronized Keys keys() {
		if (keys != null) return keys;
		var row = loadKeys();
		if (row == null) {
			KeyPair pair = WebPushCrypto.generate();
			jdbc.sql("""
					insert into public.push_vapid_keys (id, public_key, private_key) values (1, :public, :private)
					on conflict (id) do nothing
					""")
					.param("public", WebPushCrypto.b64(WebPushCrypto.rawPublic((ECPublicKey) pair.getPublic())))
					.param("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
					.update();
			row = loadKeys();
		}
		keys = new Keys(WebPushCrypto.unb64(row[0]), WebPushCrypto.privateFromPkcs8(Base64.getDecoder().decode(row[1])));
		return keys;
	}

	private String[] loadKeys() {
		return jdbc.sql("select public_key, private_key from public.push_vapid_keys where id = 1")
				.query((rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) })
				.optional().orElse(null);
	}

	@Transactional
	public void subscribe(String token, Map<String, Object> body) {
		UUID userId = auth.authenticate(token);
		DbSession.setUser(jdbc, userId);
		String endpoint = body.get("endpoint") instanceof String value ? value.trim() : null;
		Map<?, ?> keysBody = body.get("keys") instanceof Map<?, ?> value ? value : Map.of();
		String p256dh = keysBody.get("p256dh") instanceof String value ? value.trim() : null;
		String secret = keysBody.get("auth") instanceof String value ? value.trim() : null;
		if (endpoint == null || endpoint.length() > 1000 || !allowedEndpoint(endpoint)) throw new AuthException(400, "Endereço de notificação inválido.");
		if (p256dh == null || secret == null || p256dh.length() > 200 || secret.length() > 100) throw new AuthException(400, "Chaves de notificação inválidas.");
		try {
			WebPushCrypto.publicFromRaw(WebPushCrypto.unb64(p256dh));
			if (WebPushCrypto.unb64(secret).length != 16) throw new IllegalArgumentException();
		} catch (IllegalArgumentException invalid) {
			throw new AuthException(400, "Chaves de notificação inválidas.");
		}
		// O mesmo navegador pode ter sido de outra conta (troca de conta): a inscrição muda de dono.
		jdbc.sql("select public.push_forget(:endpoint)").param("endpoint", endpoint).query((rs, rowNum) -> 0).optional();
		jdbc.sql("""
				insert into public.push_subscriptions (user_id, endpoint, p256dh, auth) values (:user, :endpoint, :p256dh, :auth)
				""").param("user", userId).param("endpoint", endpoint).param("p256dh", p256dh).param("auth", secret).update();
		jdbc.sql("""
				delete from public.push_subscriptions
				 where user_id = :user and id not in (
				   select id from public.push_subscriptions where user_id = :user order by created_at desc limit :max)
				""").param("user", userId).param("max", MAX_DEVICES).update();
	}

	@Transactional
	public void unsubscribe(String token, String endpoint) {
		UUID userId = auth.authenticate(token);
		DbSession.setUser(jdbc, userId);
		if (endpoint == null || endpoint.isBlank()) return;
		jdbc.sql("delete from public.push_subscriptions where endpoint = :endpoint and user_id = :user")
				.param("endpoint", endpoint.trim()).param("user", userId).update();
	}

	/**
	 * Mensagem nova num grupo: avisa no celular os outros membros (menos quem silenciou). Quem está
	 * com o app aberto na frente não vê o aviso: o service worker confere isso no aparelho (o
	 * servidor só percebe que a conexão caiu depois de um tempo). Roda dentro da transação de quem
	 * escreveu (a função do banco só responde para membro); o envio sai depois do commit.
	 */
	public void notifyGroup(UUID groupId, String groupName, String authorName, String text) {
		try {
			List<Target> targets = jdbc.sql("select user_id, endpoint, p256dh, auth from public.chat_push_targets(:group)")
					.param("group", groupId)
					.query((rs, rowNum) -> new Target(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
					.list().stream()
					.filter(target -> allowedEndpoint(target.endpoint()))
					.toList();
			log.debug("Push do grupo {}: {} aparelho(s)", groupId, targets.size());
			if (targets.isEmpty()) return;
			long now = System.currentTimeMillis();
			if (lastSent.size() > 50_000) lastSent.values().removeIf(at -> now - at > THROTTLE_MS);
			// Todos os aparelhos da pessoa recebem; a marca de tempo só é gravada depois do filtro.
			List<Target> due = targets.stream().filter(target -> {
				Long last = lastSent.get(target.userId() + ":" + groupId);
				return last == null || now - last >= THROTTLE_MS;
			}).toList();
			due.forEach(target -> lastSent.put(target.userId() + ":" + groupId, now));
			if (due.isEmpty()) return;
			String payload = "{\"title\":\"" + json(groupName) + "\",\"body\":\"" + json(authorName + ": " + text) + "\",\"url\":\"/grupos/"
					+ groupId + "\",\"tag\":\"group-" + groupId + "\"}";
			Runnable send = () -> due.forEach(target -> executor.submit(() -> deliver(target, payload)));
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						send.run();
					}
				});
			} else {
				send.run();
			}
		} catch (RuntimeException exception) {
			// Notificação é extra: nunca impede a mensagem de ser enviada.
			log.warn("Falha ao preparar a notificação do grupo: {}", exception.getMessage());
		}
	}

	/** Envia e devolve o status do serviço de push (0 quando nem chegou a enviar). */
	int deliver(Target target, String payload) {
		try {
			Keys vapid = keys();
			byte[] body = WebPushCrypto.encrypt(payload.getBytes(StandardCharsets.UTF_8), WebPushCrypto.unb64(target.p256dh()),
					WebPushCrypto.unb64(target.auth()));
			long expires = System.currentTimeMillis() / 1000 + 12 * 3600;
			HttpRequest request = HttpRequest.newBuilder(URI.create(target.endpoint()))
					.timeout(Duration.ofSeconds(15))
					.header("Authorization", WebPushCrypto.vapidAuthorization(target.endpoint(), subject, expires, vapid.privateKey(), vapid.publicRaw()))
					.header("Content-Encoding", "aes128gcm")
					.header("Content-Type", "application/octet-stream")
					.header("TTL", "86400")
					.header("Urgency", "high")
					.POST(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();
			int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			log.debug("Push para {}: {}", URI.create(target.endpoint()).getHost(), status);
			if (status == 404 || status == 410) {
				// Inscrição expirada ou cancelada no navegador.
				jdbc.sql("select public.push_forget(:endpoint)").param("endpoint", target.endpoint()).query((rs, rowNum) -> 0).optional();
			} else if (status >= 400) {
				log.warn("Serviço de push respondeu {} para {}", status, URI.create(target.endpoint()).getHost());
			}
			return status;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		} catch (Exception exception) {
			log.warn("Falha ao enviar notificação: {}", exception.getMessage());
		}
		return 0;
	}

	/**
	 * "Testar notificação": manda um aviso para os aparelhos da própria conta e conta quantos o
	 * serviço de push aceitou. Ajuda a separar "aparelho não inscrito" de "celular segurando o aviso".
	 */
	@Transactional
	public Map<String, Object> test(String token) {
		UUID userId = auth.authenticate(token);
		DbSession.setUser(jdbc, userId);
		limits.check("push-test:" + userId, 5, Duration.ofMinutes(10));
		List<Target> mine = jdbc.sql("select user_id, endpoint, p256dh, auth from public.push_subscriptions where user_id = :user")
				.param("user", userId)
				.query((rs, rowNum) -> new Target(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
				.list();
		String payload = "{\"title\":\"Storyverse\",\"body\":\"Teste: os avisos estão chegando neste aparelho ✅\",\"url\":\"/grupos\",\"tag\":\"push-test\"}";
		long accepted = mine.stream().filter(target -> allowedEndpoint(target.endpoint()))
				.map(target -> deliver(target, payload)).filter(status -> status >= 200 && status < 300).count();
		return Map.of("devices", mine.size(), "accepted", accepted);
	}

	static boolean allowedEndpoint(String endpoint) {
		try {
			URI uri = URI.create(endpoint);
			if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getHost() == null) return false;
			String host = uri.getHost().toLowerCase();
			return HOSTS.contains(host) || HOST_SUFFIXES.stream().anyMatch(host::endsWith);
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	private static String json(String value) {
		StringBuilder out = new StringBuilder(value.length() + 8);
		for (char c : value.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		return out.toString();
	}
}
