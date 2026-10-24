package com.storyverse.api.catalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbErrors;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.storage.StorageBucket;
import com.storyverse.api.storage.StorageService;

@Service
public class CatalogService {

	private final CatalogRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final StorageService storage;
	private final CatalogTextExtractor extractor;

	public CatalogService(CatalogRepository repository, AuthService auth, AuthzService authz, JdbcClient jdbc,
			StorageService storage, CatalogTextExtractor extractor) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.storage = storage;
		this.extractor = extractor;
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

	@Transactional(readOnly = true)
	public Map<String, Object> listAdmin(String token, String status, String q, String genre, String language,
			String source, Boolean featured, int page, int limit) {
		moderator(token);
		int safeLimit = Math.min(Math.max(limit, 1), 50);
		int safePage = Math.max(page, 0);
		return Map.of("items",
				repository.listAdmin(status, q, genre, language, source, featured, safeLimit,
						safePage * safeLimit),
				"page", safePage, "limit", safeLimit);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getAdmin(String token, UUID id) {
		moderator(token);
		return repository.findById(id).orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
	}

	@Transactional
	public Map<String, Object> create(String token, Map<String, Object> body) {
		UUID actor = moderator(token);
		return createInternal(actor, body, "admin");
	}

	/** Criação usada pela importação em lote: sem autenticar de novo, com origem `import`. */
	@Transactional
	public Map<String, Object> createForImport(UUID actor, Map<String, Object> body) {
		return createInternal(actor, body, "import");
	}

	private Map<String, Object> createInternal(UUID actor, Map<String, Object> body, String source) {
		String title = required(body, "title");
		String originalPath = required(body, "originalPath");
		String originalType = required(body, "originalContentType");
		String rights = required(body, "rights");
		validateRights(rights);
		if (!originalPath.startsWith(actor + "/")) {
			throw new AuthException(400, "O arquivo precisa estar na sua pasta do catálogo.");
		}
		byte[] original = storage.read(StorageBucket.CATALOG_BOOKS, originalPath)
				.orElseThrow(() -> new AuthException(400, "Arquivo original não encontrado."));
		CatalogTextExtractor.Extracted extracted = extractor.extract(original, originalType);
		String checksum = sha256(extracted.text());
		Map<String, Object> duplicate = repository.findByChecksum(checksum).orElse(null);
		if (duplicate != null && !bool(body, "allowDuplicate", false)) {
			throw new AuthException(409,
					"Já existe um livro com este texto (" + duplicate.get("book_key") + ").",
					"DUPLICATE_BOOK");
		}
		UUID id = UUID.randomUUID();
		String bookKey = "cat:" + id;
		String textPath = storeText(actor, id, originalType, extracted, originalPath);
		String status = "published".equals(text(body, "status")) ? "published" : "draft";
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("book_key", bookKey);
		params.put("source", source);
		params.put("title", title);
		params.put("author", text(body, "author"));
		params.put("genre", text(body, "genre"));
		params.put("language", language(body, "pt"));
		params.put("series", text(body, "series"));
		params.put("series_index", decimal(body, "seriesIndex", null));
		params.put("description", text(body, "description"));
		params.put("page_count", integer(body, "pageCount", extracted.pageCount()));
		params.put("rights", rights);
		params.put("rights_note", text(body, "rightsNote"));
		params.put("text_path", textPath);
		params.put("original_path", originalPath);
		params.put("original_content_type", originalType);
		params.put("char_count", extracted.text().length());
		params.put("checksum", checksum);
		params.put("cover_url", text(body, "coverUrl"));
		params.put("status", status);
		params.put("featured", bool(body, "featured", false));
		params.put("sort_order", integer(body, "sortOrder", 0));
		params.put("published_at", "published".equals(status) ? Timestamp.from(Instant.now()) : null);
		params.put("created_by", actor);
		try {
			Map<String, Object> created = repository.insert(params)
					.orElseThrow(() -> new AuthException(400, "Não foi possível criar o livro."));
			authz.audit(actor, "catalog.create", "catalog_book", id.toString(),
					Map.of("bookKey", bookKey, "status", status, "title", title, "source", source));
			return created;
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> update(String token, UUID id, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		String rights = value(body, "rights", existingString(existing, "rights"));
		validateRights(rights);
		String status = value(body, "status", existingString(existing, "status"));
		if (!List.of("draft", "published", "hidden", "archived").contains(status)) {
			throw new AuthException(400, "Situação inválida.");
		}
		Timestamp publishedAt = timestamp(existing.get("published_at"));
		if ("published".equals(status) && publishedAt == null) publishedAt = Timestamp.from(Instant.now());
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("title", value(body, "title", existingString(existing, "title")));
		params.put("author", value(body, "author", existingString(existing, "author")));
		params.put("genre", value(body, "genre", existingString(existing, "genre")));
		params.put("language", value(body, "language", existingString(existing, "language")));
		params.put("series", value(body, "series", existingString(existing, "series")));
		params.put("series_index", body.containsKey("seriesIndex")
				? decimal(body, "seriesIndex", null) : existing.get("series_index"));
		params.put("description", value(body, "description", existingString(existing, "description")));
		params.put("page_count", body.containsKey("pageCount")
				? integer(body, "pageCount", 0) : existing.get("page_count"));
		params.put("rights", rights);
		params.put("rights_note", value(body, "rightsNote", existingString(existing, "rights_note")));
		params.put("text_path", existing.get("text_path"));
		params.put("original_path", existing.get("original_path"));
		params.put("original_content_type", existing.get("original_content_type"));
		params.put("char_count", existing.get("char_count"));
		params.put("checksum", existing.get("checksum"));
		params.put("cover_url", value(body, "coverUrl", existingString(existing, "cover_url")));
		params.put("status", status);
		params.put("featured", body.containsKey("featured") ? bool(body, "featured", false) : existing.get("featured"));
		params.put("sort_order", body.containsKey("sortOrder")
				? integer(body, "sortOrder", 0) : existing.get("sort_order"));
		params.put("published_at", publishedAt);
		try {
			Map<String, Object> updated = repository.update(params)
					.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
			authz.audit(actor, "catalog.update", "catalog_book", id.toString(),
					Map.of("bookKey", String.valueOf(existing.get("book_key")), "status", status));
			return updated;
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> replaceText(String token, UUID id, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		String originalPath = required(body, "originalPath");
		String originalType = required(body, "originalContentType");
		if (!originalPath.startsWith(actor + "/")) {
			throw new AuthException(400, "O arquivo precisa estar na sua pasta do catálogo.");
		}
		byte[] original = storage.read(StorageBucket.CATALOG_BOOKS, originalPath)
				.orElseThrow(() -> new AuthException(400, "Arquivo original não encontrado."));
		CatalogTextExtractor.Extracted extracted = extractor.extract(original, originalType);
		String checksum = sha256(extracted.text());
		Map<String, Object> duplicate = repository.findByChecksum(checksum).orElse(null);
		if (duplicate != null && !String.valueOf(duplicate.get("id")).equals(id.toString())
				&& !bool(body, "allowDuplicate", false)) {
			throw new AuthException(409,
					"Já existe um livro com este texto (" + duplicate.get("book_key") + ").",
					"DUPLICATE_BOOK");
		}
		String oldTextPath = existingString(existing, "text_path");
		String oldOriginalPath = existingString(existing, "original_path");
		String textPath = storeText(actor, id, originalType, extracted, originalPath);
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("text_path", textPath);
		params.put("original_path", originalPath);
		params.put("original_content_type", originalType);
		params.put("char_count", extracted.text().length());
		params.put("page_count", body.containsKey("pageCount")
				? integer(body, "pageCount", extracted.pageCount()) : extracted.pageCount());
		params.put("checksum", checksum);
		try {
			Map<String, Object> updated = repository.updateText(params)
					.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
			if (oldTextPath != null && !oldTextPath.equals(textPath) && !oldTextPath.equals(oldOriginalPath)
					&& "catalog-books".equals(existingString(existing, "storage_bucket"))) {
				storage.removeAfterCommit(StorageBucket.CATALOG_BOOKS, oldTextPath);
			}
			authz.audit(actor, "catalog.text", "catalog_book", id.toString(),
					Map.of("bookKey", String.valueOf(existing.get("book_key"))));
			return updated;
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> setStatus(String token, UUID id, String status) {
		UUID actor = moderator(token);
		if (!List.of("published", "hidden", "archived", "draft").contains(status)) {
			throw new AuthException(400, "Situação inválida.");
		}
		Map<String, Object> updated = repository.setStatus(id, status, "published".equals(status))
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		authz.audit(actor, "catalog." + status, "catalog_book", id.toString(),
				Map.of("bookKey", String.valueOf(updated.get("book_key"))));
		return updated;
	}

	@Transactional
	public void delete(String token, UUID id) {
		UUID actor = superadmin(token);
		Map<String, Object> existing = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		repository.delete(id);
		if ("catalog-books".equals(existingString(existing, "storage_bucket"))) {
			String textPath = existingString(existing, "text_path");
			String originalPath = existingString(existing, "original_path");
			if (textPath != null) storage.removeAfterCommit(StorageBucket.CATALOG_BOOKS, textPath);
			if (originalPath != null && !originalPath.equals(textPath)) {
				storage.removeAfterCommit(StorageBucket.CATALOG_BOOKS, originalPath);
			}
		}
		authz.audit(actor, "catalog.delete", "catalog_book", id.toString(),
				Map.of("bookKey", String.valueOf(existing.get("book_key"))));
	}

	private UUID moderator(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	private UUID superadmin(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	private String storeText(UUID actor, UUID id, String type, CatalogTextExtractor.Extracted extracted,
			String originalPath) {
		if (type.toLowerCase().startsWith("text/plain") || type.toLowerCase().startsWith("text/markdown")) {
			return originalPath;
		}
		String path = actor + "/" + id + ".txt";
		storage.write(actor, StorageBucket.CATALOG_BOOKS, path, "text/plain",
				extracted.text().getBytes(StandardCharsets.UTF_8));
		return path;
	}

	private static void validateRights(String rights) {
		if (!List.of("public_domain", "own_work", "free_license").contains(rights)) {
			throw new AuthException(400, "Direitos inválidos.");
		}
	}

	private static String required(Map<String, Object> body, String key) {
		String value = text(body, key);
		if (value == null) throw new AuthException(400, "Informe " + key + ".");
		return value;
	}

	private static String text(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value instanceof String string && !string.isBlank() ? string.strip() : null;
	}

	private static String value(Map<String, Object> body, String key, String fallback) {
		return body.containsKey(key) ? text(body, key) : fallback;
	}

	private static String language(Map<String, Object> body, String fallback) {
		String value = text(body, "language");
		return "en".equals(value) ? "en" : "pt";
	}

	private static int integer(Map<String, Object> body, String key, int fallback) {
		Object value = body.get(key);
		if (value instanceof Number number) return number.intValue();
		if (value instanceof String string) {
			try {
				return Integer.parseInt(string.trim());
			} catch (NumberFormatException ignored) {
				// usa o padrão
			}
		}
		return fallback;
	}

	private static BigDecimal decimal(Map<String, Object> body, String key, BigDecimal fallback) {
		Object value = body.get(key);
		if (value instanceof Number number) return new BigDecimal(number.toString());
		if (value instanceof String string) {
			try {
				return new BigDecimal(string.trim());
			} catch (NumberFormatException ignored) {
				// usa o padrão
			}
		}
		return fallback;
	}

	private static boolean bool(Map<String, Object> body, String key, boolean fallback) {
		Object value = body.get(key);
		return value instanceof Boolean bool ? bool : fallback;
	}

	private static String existingString(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private static Timestamp timestamp(Object value) {
		if (value == null) return null;
		if (value instanceof Timestamp timestamp) return timestamp;
		if (value instanceof String string) {
			try {
				return Timestamp.from(Instant.parse(string));
			} catch (RuntimeException ignored) {
				return null;
			}
		}
		return null;
	}

	private static String sha256(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
