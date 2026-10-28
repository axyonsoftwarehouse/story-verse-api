package com.storyverse.api.catalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.admin.ShortCache;
import com.storyverse.api.auth.AuthException;

/**
 * Contador público de leitores por livro (V30__catalog_reader_counts.sql). Só o total sai; quem leu
 * nunca. Cache curto por livro: os cards da home pedem os mesmos livros o tempo todo.
 */
@Service
public class CatalogReadersService {

	static final int MAX_KEYS = 60;
	private static final Pattern KEY = Pattern.compile("^(gb:[0-9]{1,12}|cm:[0-9a-fA-F-]{36}|cat:[0-9a-fA-F-]{36})$");

	private final JdbcClient jdbc;
	private final ShortCache cache = new ShortCache(Duration.ofMinutes(5));

	public CatalogReadersService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Total por livro, na ordem pedida. Livro sem leitor volta com 0; chave inválida é 400. */
	@Transactional(readOnly = true)
	public Map<String, Long> counts(List<String> keys) {
		Set<String> wanted = new LinkedHashSet<>();
		for (String raw : keys == null ? List.<String>of() : keys) {
			String key = raw == null ? "" : raw.trim();
			if (key.isEmpty()) {
				continue;
			}
			if (!KEY.matcher(key).matches()) {
				throw new AuthException(400, "Livro inválido.");
			}
			wanted.add(key);
		}
		if (wanted.isEmpty()) {
			throw new AuthException(400, "Informe os livros.");
		}
		if (wanted.size() > MAX_KEYS) {
			throw new AuthException(400, "No máximo " + MAX_KEYS + " livros por consulta.");
		}

		Map<String, Long> out = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (String key : wanted) {
			Long cached = cache.get("readers|" + key);
			if (cached == null) {
				missing.add(key);
			}
			out.put(key, cached == null ? 0L : cached);
		}
		if (!missing.isEmpty()) {
			Map<String, Long> found = new LinkedHashMap<>();
			jdbc.sql("select book_key, readers from public.catalog_reader_counts(cast(:keys as text[]))")
					.param("keys", missing.toArray(String[]::new))
					.query((rs, rowNum) -> found.put(rs.getString("book_key"), rs.getLong("readers")))
					.list();
			for (String key : missing) {
				long readers = found.getOrDefault(key, 0L);
				cache.put("readers|" + key, readers);
				out.put(key, readers);
			}
		}
		return out;
	}
}
