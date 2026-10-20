package com.storyverse.api.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cache compartilhado de conteúdo determinístico de IA (elenco, dica, traducao). A chave inclui
 * uma versao de prompt; subir a versao invalida sem migration. Nao guarda livro privado nem
 * conversa de personagem.
 */
@Component
public class AiCache {

	private final JdbcClient jdbc;

	public AiCache(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<String> get(String cacheKey) {
		Optional<String> content = jdbc.sql("""
				select content::text
				  from public.ai_cache
				 where cache_key = :key
				   and (expires_at is null or expires_at > now())
				""")
				.param("key", cacheKey)
				.query(String.class)
				.optional();
		content.ifPresent(key -> jdbc.sql("""
				update public.ai_cache set hits = hits + 1 where cache_key = :key
				""").param("key", cacheKey).update());
		return content;
	}

	public void put(String cacheKey, String kind, String contentJson, String model, Duration ttl) {
		jdbc.sql("""
				insert into public.ai_cache (cache_key, kind, content, model, expires_at)
				values (:key, :kind, cast(:content as jsonb), :model,
				        case when :ttlSeconds is null then null
				             else now() + make_interval(secs => cast(:ttlSeconds as double precision)) end)
				on conflict (cache_key) do update
				   set kind = excluded.kind,
				       content = excluded.content,
				       model = excluded.model,
				       expires_at = excluded.expires_at,
				       created_at = now(),
				       hits = 0
				""")
				.param("key", cacheKey)
				.param("kind", kind)
				.param("content", contentJson)
				.param("model", model)
				.param("ttlSeconds", ttl == null ? null : ttl.toSeconds())
				.update();
	}

	public static String key(String kind, String... parts) {
		StringBuilder raw = new StringBuilder(kind);
		for (String part : parts) {
			raw.append('\u001f').append(part == null ? "" : part);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
			return kind + ":" + HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponivel", exception);
		}
	}
}
