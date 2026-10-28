package com.storyverse.api.admin;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Cache em memória de validade curta para os números do painel (por instância da API). */
public final class ShortCache {

	private record Entry(Object value, long at) {
	}

	private final long ttlMs;
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

	public ShortCache(Duration ttl) {
		this.ttlMs = ttl.toMillis();
	}

	@SuppressWarnings("unchecked")
	public <T> T get(String key) {
		Entry entry = entries.get(key);
		if (entry == null) {
			return null;
		}
		if (System.currentTimeMillis() - entry.at() > ttlMs) {
			entries.remove(key);
			return null;
		}
		return (T) entry.value();
	}

	public void put(String key, Object value) {
		long now = System.currentTimeMillis();
		if (entries.size() > 100) {
			entries.entrySet().removeIf(entry -> now - entry.getValue().at() > ttlMs);
		}
		entries.put(key, new Entry(value, now));
	}

	public <T> T get(String key, Supplier<T> loader) {
		T cached = get(key);
		if (cached != null) {
			return cached;
		}
		T value = loader.get();
		put(key, value);
		return value;
	}
}
