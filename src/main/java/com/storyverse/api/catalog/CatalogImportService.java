package com.storyverse.api.catalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.storage.StorageBucket;
import com.storyverse.api.storage.StorageService;

/**
 * Importação em lote: recebe um ZIP já enviado ao bucket `catalog-imports`, lê o `metadata.csv`,
 * mostra a prévia (dry-run) e cria os livros como rascunho (`source='import'`).
 *
 * <p>O processamento é síncrono: o lote é limitado a 100 livros e a resposta já traz o relatório.
 */
@Service
public class CatalogImportService {

	private static final List<String> RIGHTS = List.of("public_domain", "own_work", "free_license");

	private final CatalogImportRepository imports;
	private final CatalogImportArchive archives;
	private final CatalogRepository catalog;
	private final CatalogService catalogService;
	private final CatalogCollectionService collections;
	private final CatalogTextExtractor extractor;
	private final StorageService storage;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;

	public CatalogImportService(CatalogImportRepository imports, CatalogImportArchive archives,
			CatalogRepository catalog, CatalogService catalogService, CatalogCollectionService collections,
			CatalogTextExtractor extractor, StorageService storage, AuthService auth, AuthzService authz,
			JdbcClient jdbc) {
		this.imports = imports;
		this.archives = archives;
		this.catalog = catalog;
		this.catalogService = catalogService;
		this.collections = collections;
		this.extractor = extractor;
		this.storage = storage;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(String token) {
		moderator(token);
		return imports.list();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(String token, UUID id) {
		moderator(token);
		return detail(id);
	}

	@Transactional(readOnly = true)
	public String errorsCsv(String token, UUID id) {
		moderator(token);
		StringBuilder csv = new StringBuilder("linha,arquivo,situacao,mensagem\n");
		for (Map<String, Object> item : imports.errors(id)) {
			csv.append(value(item.get("line"))).append(',')
					.append(quote(value(item.get("file_name")))).append(',')
					.append(value(item.get("status"))).append(',')
					.append(quote(value(item.get("message")))).append('\n');
		}
		return csv.toString();
	}

	@Transactional
	public Map<String, Object> run(String token, Map<String, Object> body) {
		UUID actor = moderator(token);
		String archivePath = required(body, "archivePath");
		if (!archivePath.startsWith(actor + "/")) {
			throw new AuthException(400, "O arquivo precisa estar na sua pasta de importações.");
		}
		boolean dryRun = bool(body, "dryRun", false);
		Path archive = storage.file(StorageBucket.CATALOG_IMPORTS, archivePath)
				.orElseThrow(() -> new AuthException(400, "Pacote de importação não encontrado."));
		String fileName = text(body, "fileName");
		if (fileName == null) fileName = archivePath.substring(archivePath.lastIndexOf('/') + 1);
		if (fileName.length() > 200) throw new AuthException(400, "Nome do arquivo grande demais.");

		Path directory;
		try {
			directory = Files.createTempDirectory("storyverse-import-");
		} catch (IOException exception) {
			throw new AuthException(500, "Não foi possível preparar a importação.");
		}
		try {
			CatalogImportArchive.Extracted extracted = archives.extract(archive, directory);
			List<Map<String, String>> rows = CatalogImportCsv.parse(read(extracted.metadata()));
			if (rows.isEmpty()) throw new AuthException(400, "O metadata.csv não tem nenhum livro.");

			UUID importId = dryRun ? null : start(actor, fileName, rows.size());
			int imported = 0;
			int skipped = 0;
			int errors = 0;
			List<Map<String, Object>> items = new ArrayList<>();
			try {
				for (int index = 0; index < rows.size(); index++) {
					Item item = process(actor, rows.get(index), index, extracted.files(), !dryRun);
					if ("imported".equals(item.status())) imported++;
					else if ("skipped".equals(item.status())) skipped++;
					else errors++;
					Map<String, Object> view = new LinkedHashMap<>();
					view.put("line", item.line());
					view.put("fileName", item.file());
					view.put("status", item.status());
					view.put("message", item.message());
					view.put("bookKey", item.bookKey());
					items.add(view);
					if (importId != null) saveItem(importId, item);
				}
			} catch (RuntimeException exception) {
				if (importId != null) finish(importId, "failed", imported, skipped, errors, message(exception));
				throw exception;
			}

			if (importId == null) {
				Map<String, Object> preview = new LinkedHashMap<>();
				preview.put("dryRun", true);
				preview.put("total", rows.size());
				preview.put("items", items);
				return preview;
			}

			finish(importId, "done", imported, skipped, errors, null);
			authz.audit(actor, "catalog.import", "catalog_import", importId.toString(),
					Map.of("file", fileName, "total", rows.size(), "imported", imported, "skipped", skipped,
							"errors", errors));
			return detail(importId);
		} finally {
			deleteTree(directory);
		}
	}

	private Item process(UUID actor, Map<String, String> row, int index, Map<String, Path> files,
			boolean execute) {
		int line = line(row, index);
		String file = value(row, "file");
		if (file == null) return new Item(line, null, "error", "Informe a coluna file.", null);
		Path path = CatalogImportArchive.file(files, file);
		if (path == null) return new Item(line, file, "error", "Arquivo não encontrado no ZIP.", null);

		String titleRow = value(row, "title");
		String rights = value(row, "rights");
		if (rights == null || !RIGHTS.contains(rights)) {
			return new Item(line, file, "error", "Direitos inválidos (public_domain, own_work ou free_license).", null);
		}
		String type = contentTypeFor(file);
		if (type == null) return new Item(line, file, "error", "Formato não aceito. Use TXT, MD, EPUB ou PDF.", null);
		BigDecimal seriesIndex = decimal(value(row, "series_index"));
		if (seriesIndex == null && value(row, "series_index") != null) {
			return new Item(line, file, "error", "Volume da série inválido.", null);
		}
		Integer pageCount = integer(value(row, "page_count"));
		if (pageCount == null && value(row, "page_count") != null) {
			return new Item(line, file, "error", "Número de páginas inválido.", null);
		}

		String collectionSlug = value(row, "collection");
		UUID collectionId = null;
		if (collectionSlug != null) {
			collectionId = collections.idBySlug(collectionSlug);
			if (collectionId == null) {
				return new Item(line, file, "error", "Coleção não encontrada: " + collectionSlug, null);
			}
		}

		byte[] bytes;
		CatalogTextExtractor.Extracted extracted;
		try {
			bytes = Files.readAllBytes(path);
			extracted = extractor.extract(bytes, type);
		} catch (AuthException exception) {
			return new Item(line, file, "error", exception.getMessage(), null);
		} catch (IOException exception) {
			return new Item(line, file, "error", "Não foi possível ler o arquivo.", null);
		}
		String title = titleRow != null ? titleRow : extracted.title();
		if (title == null) title = titleFromFile(file);
		if (title == null) {
			return new Item(line, file, "error", "Informe o t?tulo ou use um arquivo com metadados.", null);
		}
		String author = value(row, "author") != null ? value(row, "author") : extracted.author();
		String language = value(row, "language") != null ? value(row, "language") : extracted.language();
		String checksum = sha256(extracted.text());
		Optional<Map<String, Object>> duplicate = catalog.findByChecksum(checksum);
		if (duplicate.isPresent()) {
			return new Item(line, file, "skipped",
					"Já existe um livro com este texto (" + duplicate.get().get("book_key") + ").", null);
		}
		if (!execute) {
			return new Item(line, file, "ok", "Pronto para importar.", null);
		}

		UUID id = UUID.randomUUID();
		String extension = extensionFor(file);
		String originalPath = actor + "/" + id + extension;
		try {
			storage.write(actor, StorageBucket.CATALOG_BOOKS, originalPath, type, bytes);
		} catch (RuntimeException exception) {
			return new Item(line, file, "error", message(exception), null);
		}
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("title", title);
			body.put("author", author);
			body.put("genre", value(row, "genre"));
			body.put("language", "en".equals(language) ? "en" : "pt");
			body.put("series", value(row, "series"));
			body.put("seriesIndex", seriesIndex);
			body.put("description", value(row, "description"));
			body.put("pageCount", pageCount != null ? pageCount : extracted.pageCount());
			body.put("rights", rights);
			body.put("rightsNote", value(row, "rights_note"));
			body.put("coverUrl", value(row, "cover_url"));
			body.put("status", "draft");
			body.put("originalPath", originalPath);
			body.put("originalContentType", type);
			Map<String, Object> created = catalogService.createForImport(actor, body);
			UUID bookId = UUID.fromString(String.valueOf(created.get("id")));
			String bookKey = String.valueOf(created.get("book_key"));
			if (collectionId != null) {
				try {
					collections.addBookInternal(collectionId, bookId, index);
				} catch (RuntimeException ignored) {
					// O livro entrou; a coleção pode ser ajustada depois no painel.
				}
			}
			return new Item(line, file, "imported", null, bookKey);
		} catch (AuthException exception) {
			storage.removeAfterCommit(StorageBucket.CATALOG_BOOKS, originalPath);
			return new Item(line, file, exception.status() == 409 ? "skipped" : "error",
					exception.getMessage(), null);
		} catch (RuntimeException exception) {
			storage.removeAfterCommit(StorageBucket.CATALOG_BOOKS, originalPath);
			return new Item(line, file, "error", message(exception), null);
		}
	}

	private UUID start(UUID actor, String fileName, int total) {
		Map<String, Object> params = new LinkedHashMap<>();
		UUID id = UUID.randomUUID();
		params.put("id", id);
		params.put("created_by", actor);
		params.put("status", "running");
		params.put("file_name", fileName);
		params.put("total", total);
		imports.insert(params);
		return id;
	}

	private void saveItem(UUID importId, Item item) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("import", importId);
		params.put("line", item.line());
		params.put("file", item.file());
		params.put("status", item.status());
		params.put("message", item.message());
		params.put("book", item.bookId());
		imports.insertItem(params);
	}

	private void finish(UUID id, String status, int imported, int skipped, int errors, String error) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("status", status);
		params.put("total", imported + skipped + errors);
		params.put("imported", imported);
		params.put("skipped", skipped);
		params.put("error", error);
		imports.update(params);
	}

	private Map<String, Object> detail(UUID id) {
		Map<String, Object> row = imports.find(id)
				.orElseThrow(() -> new AuthException(404, "Importação não encontrada."));
		row.put("items", imports.items(id));
		return row;
	}

	private UUID moderator(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		DbSession.setUser(jdbc, actor);
		return actor;
	}

	private static String read(Path path) {
		try {
			byte[] bytes = Files.readAllBytes(path);
			if (bytes.length > CatalogImportCsv.MAX_BYTES) throw new AuthException(413, "O metadata.csv é grande demais.");
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new AuthException(400, "Não foi possível ler o metadata.csv.");
		}
	}

	private static int line(Map<String, String> row, int index) {
		try {
			return Integer.parseInt(row.getOrDefault("_line", String.valueOf(index + 2)));
		} catch (NumberFormatException exception) {
			return index + 2;
		}
	}

	private static String contentTypeFor(String file) {
		String lower = file.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/plain";
		if (lower.endsWith(".pdf")) return "application/pdf";
		if (lower.endsWith(".epub")) return "application/epub+zip";
		return null;
	}

	private static String extensionFor(String file) {
		int dot = file.lastIndexOf('.');
		return dot >= 0 ? file.substring(dot).toLowerCase(Locale.ROOT) : ".txt";
	}

	private static String titleFromFile(String file) {
		String base = file.replaceAll("\\.[^.]+$", "")
				.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").strip();
		return base.isBlank() ? null : base;
	}

	private static BigDecimal decimal(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return new BigDecimal(value.trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static Integer integer(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String value(Map<String, String> row, String key) {
		String value = row.get(key);
		return value == null || value.isBlank() ? null : value.trim();
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

	private static boolean bool(Map<String, Object> body, String key, boolean fallback) {
		Object value = body.get(key);
		return value instanceof Boolean bool ? bool : fallback;
	}

	private static String value(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String quote(String value) {
		String text = value == null ? "" : value;
		return '"' + text.replace("\"", "\"\"") + '"';
	}

	private static String message(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	private static String sha256(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void deleteTree(Path directory) {
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Temporário: o sistema limpa depois.
				}
			});
		} catch (IOException ignored) {
			// Temporário: o sistema limpa depois.
		}
	}

	private record Item(int line, String file, String status, String message, String bookKey) {
		UUID bookId() {
			try {
				return bookKey == null ? null : UUID.fromString(bookKey.substring(4));
			} catch (RuntimeException exception) {
				return null;
			}
		}
	}
}
