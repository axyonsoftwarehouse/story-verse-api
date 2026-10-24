package com.storyverse.api.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

/** Coleções do catálogo: curadoria manual ("Clássicos", "Para começar"), com ordem e publicação. */
@Service
public class CatalogCollectionService {

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{2,80}$");
	private static final List<String> STATUSES = List.of("draft", "published", "hidden");

	private final CatalogCollectionRepository repository;
	private final CatalogRepository books;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;

	public CatalogCollectionService(CatalogCollectionRepository repository, CatalogRepository books,
			AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.repository = repository;
		this.books = books;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(String token) {
		moderator(token);
		return repository.listAll();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(String token, UUID id) {
		moderator(token);
		return detail(id);
	}

	@Transactional
	public Map<String, Object> create(String token, Map<String, Object> body) {
		UUID actor = moderator(token);
		String slug = requiredSlug(body, null);
		String title = requiredText(body, "title", 120, "Informe o título da coleção.");
		String description = text(body, "description");
		String status = status(body, "draft");
		int sortOrder = integer(body, "sortOrder", 0);
		if (repository.findBySlug(slug).isPresent()) {
			throw new AuthException(409, "Já existe uma coleção com este endereço.", "COLLECTION_SLUG");
		}
		Map<String, Object> params = new LinkedHashMap<>();
		UUID id = UUID.randomUUID();
		params.put("id", id);
		params.put("slug", slug);
		params.put("title", title);
		params.put("description", description);
		params.put("status", status);
		params.put("sort_order", sortOrder);
		Map<String, Object> created = repository.insert(params)
				.orElseThrow(() -> new AuthException(400, "Não foi possível criar a coleção."));
		authz.audit(actor, "collection.create", "catalog_collection", id.toString(),
				Map.of("slug", slug, "title", title, "status", status));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> update(String token, UUID id, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		String slug = body.containsKey("slug") ? requiredSlug(body, string(existing.get("slug")))
				: string(existing.get("slug"));
		String title = body.containsKey("title")
				? requiredText(body, "title", 120, "Informe o título da coleção.")
				: string(existing.get("title"));
		String description = body.containsKey("description") ? text(body, "description")
				: string(existing.get("description"));
		String status = body.containsKey("status") ? status(body, string(existing.get("status")))
				: string(existing.get("status"));
		int sortOrder = body.containsKey("sortOrder") ? integer(body, "sortOrder", 0)
				: number(existing.get("sort_order"));
		repository.findBySlug(slug).filter(other -> !id.equals(other.get("id"))).ifPresent(other -> {
			throw new AuthException(409, "Já existe uma coleção com este endereço.", "COLLECTION_SLUG");
		});
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("slug", slug);
		params.put("title", title);
		params.put("description", description);
		params.put("status", status);
		params.put("sort_order", sortOrder);
		repository.update(params).orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		authz.audit(actor, "collection.update", "catalog_collection", id.toString(),
				Map.of("slug", slug, "title", title, "status", status));
		return detail(id);
	}

	@Transactional
	public void delete(String token, UUID id) {
		UUID actor = moderator(token);
		Map<String, Object> existing = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		repository.delete(id);
		authz.audit(actor, "collection.delete", "catalog_collection", id.toString(),
				Map.of("slug", string(existing.get("slug")), "title", string(existing.get("title"))));
	}

	/** Troca a lista inteira de livros da coleção; a ordem do array vira a ordem da estante. */
	@Transactional
	public Map<String, Object> setBooks(String token, UUID id, Map<String, Object> body) {
		UUID actor = moderator(token);
		repository.findById(id).orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		List<UUID> ids = ids(body);
		if (ids.size() > 500) throw new AuthException(400, "Uma coleção aceita no máximo 500 livros.");
		for (UUID bookId : ids) {
			if (books.findById(bookId).isEmpty()) throw new AuthException(400, "Livro não encontrado: " + bookId);
		}
		repository.replaceBooks(id, ids);
		authz.audit(actor, "collection.books", "catalog_collection", id.toString(),
				Map.of("books", ids.size()));
		return detail(id);
	}

	/** Para a importação em lote: adiciona sem repetir e sem falhar se a coleção sumiu. */
	@Transactional
	public void addBookInternal(UUID collectionId, UUID bookId, int sortOrder) {
		repository.addBook(collectionId, bookId, sortOrder);
	}

	@Transactional(readOnly = true)
	public UUID idBySlug(String slug) {
		return repository.findBySlug(slug).map(row -> UUID.fromString(String.valueOf(row.get("id")))).orElse(null);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> publicList() {
		List<Map<String, Object>> collections = new ArrayList<>();
		for (Map<String, Object> collection : repository.listPublished()) {
			UUID id = UUID.fromString(String.valueOf(collection.get("id")));
			collection.put("books", repository.publishedBooks(id));
			collections.add(collection);
		}
		return collections;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> publicGet(String slug) {
		Map<String, Object> collection = repository.findBySlug(slug)
				.filter(row -> "published".equals(row.get("status")))
				.orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		UUID id = UUID.fromString(String.valueOf(collection.get("id")));
		collection.put("books", repository.publishedBooks(id));
		return collection;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> collectionsForBookKey(String bookKey) {
		return repository.collectionsForBookKey(bookKey);
	}

	private Map<String, Object> detail(UUID id) {
		Map<String, Object> collection = repository.findById(id)
				.orElseThrow(() -> new AuthException(404, "Coleção não encontrada."));
		collection.put("books", repository.adminBooks(id));
		return collection;
	}

	private UUID moderator(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	private static List<UUID> ids(Map<String, Object> body) {
		Object raw = body.get("bookIds") != null ? body.get("bookIds") : body.get("books");
		if (!(raw instanceof List<?> list)) throw new AuthException(400, "Informe os livros da coleção.");
		List<UUID> ids = new ArrayList<>();
		for (Object item : list) {
			try {
				ids.add(UUID.fromString(String.valueOf(item)));
			} catch (RuntimeException exception) {
				throw new AuthException(400, "Livro inválido na coleção.");
			}
		}
		return ids;
	}

	private static String requiredSlug(Map<String, Object> body, String fallback) {
		String slug = text(body, "slug");
		if (slug == null) slug = fallback;
		if (slug == null || !SLUG.matcher(slug).matches()) {
			throw new AuthException(400, "Endereço da coleção inválido: use letras minúsculas, números e hífen.");
		}
		return slug;
	}

	private static String requiredText(Map<String, Object> body, String key, int max, String message) {
		String value = text(body, key);
		if (value == null) throw new AuthException(400, message);
		if (value.length() > max) throw new AuthException(400, "Texto grande demais.");
		return value;
	}

	private static String status(Map<String, Object> body, String fallback) {
		String status = text(body, "status");
		if (status == null) status = fallback;
		if (status == null || !STATUSES.contains(status)) throw new AuthException(400, "Situação inválida.");
		return status;
	}

	private static String text(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value instanceof String string && !string.isBlank() ? string.strip() : null;
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

	private static int number(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
