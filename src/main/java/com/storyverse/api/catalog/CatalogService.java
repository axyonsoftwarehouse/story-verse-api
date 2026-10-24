package com.storyverse.api.catalog;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.storage.StorageBucket;
import com.storyverse.api.storage.StorageService;

@Service
public class CatalogService {

	private final CatalogRepository repository;
	private final AuthService auth;
	private final StorageService storage;

	public CatalogService(CatalogRepository repository, AuthService auth, StorageService storage) {
		this.repository = repository;
		this.auth = auth;
		this.storage = storage;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(String q, String genre, String language, String author, String collection,
			Boolean featured, String sort, int page, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), 50);
		int safePage = Math.max(page, 0);
		List<Map<String, Object>> items = repository.listPublished(q, genre, language, author, collection,
				featured, sort, safeLimit, safePage * safeLimit);
		return Map.of("items", items, "page", safePage, "limit", safeLimit);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(String bookKey) {
		return repository.findPublishedByKey(bookKey)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
	}

	@Transactional(readOnly = true)
	public String text(String bookKey, String accessToken) {
		auth.authenticate(accessToken);
		Map<String, Object> row = repository.findTextByKey(bookKey)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		StorageBucket bucket = StorageBucket.from(String.valueOf(row.get("storage_bucket")));
		byte[] bytes = storage.read(bucket, String.valueOf(row.get("text_path")))
				.orElseThrow(() -> new AuthException(404, "Texto do livro indisponível."));
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
