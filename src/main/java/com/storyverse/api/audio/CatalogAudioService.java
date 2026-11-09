package com.storyverse.api.audio;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.billing.BillingService;
import com.storyverse.api.catalog.CatalogRepository;
import com.storyverse.api.common.DbErrors;
import com.storyverse.api.common.DbSession;

/** Cadastro e curadoria dos audiolivros do catálogo. */
@Service
public class CatalogAudioService {

	private static final long MAX_AUDIO_BYTES = 1L * 1024 * 1024 * 1024;
	private static final List<String> LANGUAGES = List.of("pt", "en");
	private static final List<String> SOURCES = List.of("librivox", "admin", "partner");
	private static final List<String> RIGHTS = List.of("public_domain", "own_work", "free_license");
	private static final List<String> TEXT_MAPPINGS = List.of("pending", "mapped", "none");
	private static final Map<String, String> AUDIO_TYPES = Map.of(
			"mp3", "audio/mpeg",
			"m4a", "audio/mp4",
			"m4b", "audio/mp4",
			"aac", "audio/aac",
			"ogg", "audio/ogg",
			"opus", "audio/ogg",
			"wav", "audio/wav");

	private final CatalogAudioRepository repository;
	private final CatalogRepository books;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final R2Storage r2;
	private final BillingService billing;

	public CatalogAudioService(CatalogAudioRepository repository, CatalogRepository books, AuthService auth,
			AuthzService authz, JdbcClient jdbc, R2Storage r2, BillingService billing) {
		this.repository = repository;
		this.books = books;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.r2 = r2;
		this.billing = billing;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(String token, UUID bookId) {
		moderator(token);
		requireBook(bookId);
		List<Map<String, Object>> editions = repository.listEditions(bookId).stream()
				.map(this::withChapters)
				.toList();
		return Map.of("editions", editions);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> publicList() {
		List<Map<String, Object>> audiobooks = repository.listPublishedEditions().stream()
				.map(row -> {
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("bookKey", row.get("book_key"));
					out.put("title", row.get("book_title"));
					out.put("author", row.get("book_author"));
					out.put("coverUrl", row.get("cover_url") != null
							? row.get("cover_url")
							: row.get("book_cover_url"));
					out.put("narrator", row.get("narrator"));
					out.put("language", row.get("language"));
					out.put("totalDurationSeconds", row.get("total_duration_seconds"));
					out.put("chapterCount", row.get("chapter_count"));
					out.put("rights", row.get("rights"));
					out.put("licenseNote", row.get("license_note"));
					out.put("publishedAt", row.get("published_at"));
					return out;
				})
				.toList();
		return Map.of("audiobooks", audiobooks);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> publicManifest(String bookKey) {
		Map<String, Object> book = repository.findPublishedBookByKey(bookKey)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		String bookCover = existingString(book, "cover_url");
		List<Map<String, Object>> editions = repository.listEditions(uuid(book.get("id"))).stream()
				.filter(edition -> "published".equals(existingString(edition, "status")))
				.map(edition -> publicEdition(edition, bookCover))
				.toList();
		Map<String, Object> bookOut = new LinkedHashMap<>();
		bookOut.put("bookKey", book.get("book_key"));
		bookOut.put("title", book.get("title"));
		bookOut.put("author", book.get("author"));
		bookOut.put("coverUrl", bookCover);
		return Map.of("book", bookOut, "editions", editions);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> publicStream(String token, String bookKey, UUID audioId, int chapterIndex) {
		if (chapterIndex <= 0) throw new AuthException(404, "Capítulo não encontrado.");
		Map<String, Object> book = repository.findPublishedBookByKey(bookKey)
				.orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
		Map<String, Object> edition = repository.findEdition(audioId)
				.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
		if (!String.valueOf(book.get("book_key")).equals(String.valueOf(edition.get("book_key")))
				|| !"published".equals(existingString(edition, "status"))) {
			throw new AuthException(404, "Audiolivro não encontrado.");
		}
		Map<String, Object> chapter = null;
		if (chapterIndex == 1) {
			chapter = repository.findChapter(audioId, chapterIndex)
					.orElseThrow(() -> new AuthException(404, "Capítulo não encontrado."));
		}
		if (chapter == null || !freeSample(chapterIndex, edition.get("chapter_count"), chapter.get("duration_seconds"))) {
			UUID user = auth.authenticate(token);
			billing.requireAccess(user);
			DbSession.setUser(jdbc, user);
		}
		if (chapter == null) {
			chapter = repository.findChapter(audioId, chapterIndex)
					.orElseThrow(() -> new AuthException(404, "Capítulo não encontrado."));
		}
		String url = r2.presignGet(String.valueOf(chapter.get("audio_path")), Duration.ofHours(1));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("url", url);
		out.put("startSeconds", chapter.get("start_seconds"));
		out.put("endSeconds", chapter.get("end_seconds"));
		out.put("durationSeconds", chapter.get("duration_seconds"));
		out.put("mimeType", chapter.get("mime_type"));
		out.put("expiresInSeconds", 3600);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> progress(String token, UUID audioId) {
		UUID user = auth.authenticate(token);
		DbSession.setUser(jdbc, user);
		return repository.findProgress(user, audioId).map(this::progressMap).orElseGet(() -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("chapterIndex", 1);
			out.put("positionSeconds", 0);
			out.put("playbackRate", 1.0);
			out.put("completed", false);
			out.put("updatedAt", null);
			return out;
		});
	}

	@Transactional
	public Map<String, Object> saveProgress(String token, UUID audioId, Map<String, Object> body) {
		UUID user = auth.authenticate(token);
		DbSession.setUser(jdbc, user);
		requirePublishedEdition(audioId);
		int chapterIndex = integer(body, "chapterIndex", 1);
		if (chapterIndex <= 0) throw new AuthException(400, "Capítulo inválido.");
		Map<String, Object> chapter = repository.findChapter(audioId, chapterIndex)
				.orElseThrow(() -> new AuthException(404, "Capítulo não encontrado."));
		int position = integer(body, "positionSeconds", 0);
		int duration = Math.max(1, number(chapter.get("duration_seconds")));
		if (position < 0 || position > duration) {
			throw new AuthException(400, "Posição inválida.");
		}
		double rate = doubleValue(body, "playbackRate", 1.0);
		if (rate < 0.5 || rate > 2.0) throw new AuthException(400, "Velocidade inválida.");
		boolean completed = Boolean.TRUE.equals(body.get("completed"));
		return repository.upsertProgress(user, audioId, chapterIndex, position, rate, completed)
				.map(this::progressMap)
				.orElseThrow(() -> new AuthException(400, "Não foi possível salvar o progresso."));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(String token, UUID audioId) {
		moderator(token);
		return detail(audioId);
	}

	@Transactional
	public Map<String, Object> create(String token, UUID bookId, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> book = requireBook(bookId);
		String language = language(body, "pt");
		String source = source(body, "librivox");
		String rights = rights(body, null);
		String textMapping = textMapping(body, "pending");
		UUID id = UUID.randomUUID();
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("book_id", bookId);
		params.put("language", language);
		params.put("narrator", text(body, "narrator"));
		params.put("source", source);
		params.put("source_url", text(body, "sourceUrl"));
		params.put("rights", rights);
		params.put("rights_note", text(body, "rightsNote"));
		params.put("license_note", text(body, "licenseNote"));
		params.put("text_mapping", textMapping);
		params.put("cover_url", text(body, "coverUrl"));
		params.put("created_by", actor);
		try {
			repository.insertEdition(params)
					.orElseThrow(() -> new AuthException(400, "Não foi possível criar a edição de áudio."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
		authz.audit(actor, "catalog.audio.create", "catalog_book_audio", id.toString(),
				Map.of("bookKey", String.valueOf(book.get("book_key")), "language", language));
		return detail(id);
	}

	@Transactional
	public Map<String, Object> update(String token, UUID audioId, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = requireEdition(audioId);
		String language = body.containsKey("language")
				? language(body, existingString(existing, "language"))
				: existingString(existing, "language");
		String source = body.containsKey("source")
				? source(body, existingString(existing, "source"))
				: existingString(existing, "source");
		String rights = body.containsKey("rights")
				? rights(body, existingString(existing, "rights"))
				: existingString(existing, "rights");
		String textMapping = body.containsKey("textMapping")
				? textMapping(body, existingString(existing, "text_mapping"))
				: existingString(existing, "text_mapping");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", audioId);
		params.put("language", language);
		params.put("narrator", value(body, "narrator", existingString(existing, "narrator")));
		params.put("source", source);
		params.put("source_url", value(body, "sourceUrl", existingString(existing, "source_url")));
		params.put("rights", rights);
		params.put("rights_note", value(body, "rightsNote", existingString(existing, "rights_note")));
		params.put("license_note", value(body, "licenseNote", existingString(existing, "license_note")));
		params.put("text_mapping", textMapping);
		params.put("cover_url", value(body, "coverUrl", existingString(existing, "cover_url")));
		try {
			repository.updateEdition(params)
					.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
		authz.audit(actor, "catalog.audio.update", "catalog_book_audio", audioId.toString(),
				Map.of("bookKey", String.valueOf(existing.get("book_key"))));
		return detail(audioId);
	}

	@Transactional
	public Map<String, Object> uploadUrl(String token, UUID audioId, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = requireEdition(audioId);
		if (!r2.configured()) {
			throw new AuthException(503, "Armazenamento de áudio não configurado.");
		}
		String fileName = required(body, "fileName");
		String contentType = required(body, "contentType");
		long size = longValue(body, "sizeBytes", -1);
		if (size <= 0) throw new AuthException(400, "Informe o tamanho do arquivo de áudio.");
		if (size > MAX_AUDIO_BYTES) throw new AuthException(413, "Arquivo de áudio grande demais (máximo de 1 GB).");
		String extension = extension(fileName);
		String type = audioType(contentType, extension);
		if (type == null) throw new AuthException(415, "Formato de áudio não aceito.");
		String key = "books/" + existing.get("book_key") + "/" + audioId + "/" + UUID.randomUUID() + extension;
		String url = r2.presignPut(key, type, Duration.ofMinutes(15));
		authz.audit(actor, "catalog.audio.upload", "catalog_book_audio", audioId.toString(),
				Map.of("path", key, "sizeBytes", size));
		return Map.of("uploadUrl", url, "path", key);
	}

	@Transactional
	public Map<String, Object> createChapter(String token, UUID audioId, Map<String, Object> body) {
		UUID actor = moderator(token);
		Map<String, Object> existing = requireEdition(audioId);
		String path = required(body, "path");
		String prefix = "books/" + existing.get("book_key") + "/" + audioId + "/";
		if (!path.startsWith(prefix)) {
			throw new AuthException(400, "Caminho do áudio inválido.");
		}
		int index = integer(body, "chapterIndex", 0);
		if (index <= 0) throw new AuthException(400, "Informe o número do capítulo.");
		if (repository.findChapter(audioId, index).isPresent()) {
			throw new AuthException(409, "Já existe um capítulo com este número.", "AUDIO_CHAPTER_EXISTS");
		}
		int start = integer(body, "startSeconds", 0);
		Integer end = optionalInteger(body.get("endSeconds"));
		int duration = integer(body, "durationSeconds", -1);
		if (start < 0) throw new AuthException(400, "Tempo inicial inválido.");
		if (end != null && end < start) throw new AuthException(400, "O fim do capítulo é antes do início.");
		if (end != null) duration = end - start;
		if (duration <= 0) throw new AuthException(400, "A duração do capítulo precisa ser maior que zero.");
		String title = text(body, "title");
		if (title != null && title.length() > 200) throw new AuthException(400, "Título do capítulo grande demais.");
		Integer textChapter = optionalInteger(body.get("textChapterIndex"));
		Integer textChapterEnd = optionalInteger(body.get("textChapterEndIndex"));
		validateTextRange(textChapter, textChapterEnd);
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("audio_id", audioId);
		params.put("chapter_index", index);
		params.put("title", title);
		params.put("audio_path", path);
		params.put("mime_type", mimeFor(path));
		params.put("duration_seconds", duration);
		params.put("start_seconds", start);
		params.put("end_seconds", end);
		params.put("size_bytes", optionalLong(body.get("sizeBytes")));
		params.put("checksum", text(body, "checksum"));
		params.put("text_chapter_index", textChapter);
		params.put("text_chapter_end_index", textChapterEnd);
		params.put("status", "ready");
		Map<String, Object> chapter;
		try {
			chapter = repository.insertChapter(params)
					.orElseThrow(() -> new AuthException(400, "Não foi possível registrar o capítulo."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
		repository.recalculate(audioId);
		authz.audit(actor, "catalog.audio.chapter.create", "catalog_audio_chapter",
				String.valueOf(chapter.get("id")), Map.of("audioId", audioId.toString(), "chapterIndex", index));
		return chapter;
	}

	@Transactional
	public Map<String, Object> updateChapter(String token, UUID audioId, int chapterIndex, Map<String, Object> body) {
		UUID actor = moderator(token);
		requireEdition(audioId);
		Map<String, Object> existing = repository.findChapter(audioId, chapterIndex)
				.orElseThrow(() -> new AuthException(404, "Capítulo não encontrado."));
		int start = body.containsKey("startSeconds")
				? integer(body, "startSeconds", 0)
				: number(existing.get("start_seconds"));
		Integer end = body.containsKey("endSeconds")
				? optionalInteger(body.get("endSeconds"))
				: optionalInteger(existing.get("end_seconds"));
		if (start < 0) throw new AuthException(400, "Tempo inicial inválido.");
		if (end != null && end < start) throw new AuthException(400, "O fim do capítulo é antes do início.");
		int duration = end != null ? end - start : number(existing.get("duration_seconds"));
		if (body.containsKey("durationSeconds")) duration = integer(body, "durationSeconds", duration);
		if (duration <= 0) throw new AuthException(400, "A duração do capítulo precisa ser maior que zero.");
		Integer textChapter = body.containsKey("textChapterIndex")
				? optionalInteger(body.get("textChapterIndex"))
				: optionalInteger(existing.get("text_chapter_index"));
		Integer textChapterEnd = body.containsKey("textChapterEndIndex")
				? optionalInteger(body.get("textChapterEndIndex"))
				: optionalInteger(existing.get("text_chapter_end_index"));
		validateTextRange(textChapter, textChapterEnd);
		String title = body.containsKey("title") ? text(body, "title") : existingString(existing, "title");
		if (title != null && title.length() > 200) throw new AuthException(400, "Título do capítulo grande demais.");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("audio_id", audioId);
		params.put("chapter_index", chapterIndex);
		params.put("title", title);
		params.put("text_chapter_index", textChapter);
		params.put("text_chapter_end_index", textChapterEnd);
		params.put("start_seconds", start);
		params.put("end_seconds", end);
		params.put("duration_seconds", duration);
		try {
			repository.updateChapter(params)
					.orElseThrow(() -> new AuthException(404, "Capítulo não encontrado."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
		repository.recalculate(audioId);
		authz.audit(actor, "catalog.audio.chapter.update", "catalog_audio_chapter",
				String.valueOf(existing.get("id")), Map.of("audioId", audioId.toString(), "chapterIndex", chapterIndex));
		return repository.findChapter(audioId, chapterIndex).orElseThrow();
	}

	@Transactional
	public Map<String, Object> publish(String token, UUID audioId) {
		UUID actor = moderator(token);
		Map<String, Object> existing = requireEdition(audioId);
		List<Map<String, Object>> chapters = repository.listChapters(audioId);
		if (chapters.isEmpty()) throw new AuthException(400, "Envie pelo menos um capítulo antes de publicar.");
		int total = chapters.stream().mapToInt(chapter -> number(chapter.get("duration_seconds"))).sum();
		if (total <= 0) throw new AuthException(400, "A duração total do áudio precisa ser maior que zero.");
		if (RIGHTS.stream().noneMatch(rights -> rights.equals(existingString(existing, "rights")))) {
			throw new AuthException(400, "Direitos inválidos.");
		}
		String textMapping = existingString(existing, "text_mapping");
		if (textMapping == null || "pending".equals(textMapping)) {
			throw new AuthException(400, "Defina o mapeamento do texto (mapeado ou sem texto) antes de publicar.");
		}
		if ("mapped".equals(textMapping)
				&& chapters.stream().noneMatch(chapter -> chapter.get("text_chapter_index") != null)) {
			throw new AuthException(400, "Nenhum capítulo está ligado ao texto.");
		}
		if (!"public_domain".equals(existingString(existing, "rights"))
				&& blank(existingString(existing, "rights_note"))) {
			throw new AuthException(400, "Explique por que este áudio pode ser publicado.");
		}
		repository.setStatus(audioId, "published", true)
				.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
		authz.audit(actor, "catalog.audio.publish", "catalog_book_audio", audioId.toString(),
				Map.of("bookKey", String.valueOf(existing.get("book_key")), "chapters", chapters.size()));
		return detail(audioId);
	}

	@Transactional
	public Map<String, Object> hide(String token, UUID audioId) {
		UUID actor = moderator(token);
		Map<String, Object> existing = requireEdition(audioId);
		repository.setStatus(audioId, "hidden", false)
				.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
		authz.audit(actor, "catalog.audio.hide", "catalog_book_audio", audioId.toString(),
				Map.of("bookKey", String.valueOf(existing.get("book_key"))));
		return detail(audioId);
	}

	@Transactional
	public void delete(String token, UUID audioId) {
		UUID actor = superadmin(token);
		Map<String, Object> existing = requireEdition(audioId);
		repository.deleteEdition(audioId);
		authz.audit(actor, "catalog.audio.delete", "catalog_book_audio", audioId.toString(),
				Map.of("bookKey", String.valueOf(existing.get("book_key"))));
	}

	private Map<String, Object> detail(UUID audioId) {
		return repository.findEdition(audioId).map(this::withChapters)
				.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
	}

	private Map<String, Object> withChapters(Map<String, Object> edition) {
		Map<String, Object> out = new LinkedHashMap<>(edition);
		out.put("chapters", repository.listChapters(uuid(edition.get("id"))));
		return out;
	}

	private Map<String, Object> publicEdition(Map<String, Object> edition, String bookCover) {
		List<Map<String, Object>> chapters = repository.listChapters(uuid(edition.get("id"))).stream()
				.map(chapter -> {
					int index = number(chapter.get("chapter_index"));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("index", index);
					out.put("title", chapter.get("title"));
					out.put("durationSeconds", chapter.get("duration_seconds"));
					out.put("startSeconds", chapter.get("start_seconds"));
					out.put("endSeconds", chapter.get("end_seconds"));
					out.put("textChapterIndex", chapter.get("text_chapter_index"));
					out.put("textChapterEndIndex", chapter.get("text_chapter_end_index"));
					out.put("free", freeSample(index, edition.get("chapter_count"), chapter.get("duration_seconds")));
					return out;
				})
				.toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", edition.get("id"));
		out.put("language", edition.get("language"));
		out.put("narrator", edition.get("narrator"));
		out.put("source", edition.get("source"));
		out.put("sourceUrl", edition.get("source_url"));
		out.put("rights", edition.get("rights"));
		out.put("licenseNote", edition.get("license_note"));
		out.put("totalDurationSeconds", edition.get("total_duration_seconds"));
		out.put("chapterCount", edition.get("chapter_count"));
		out.put("coverUrl", edition.get("cover_url") != null ? edition.get("cover_url") : bookCover);
		// Sem amostra aberta (capítulo único ou primeiro capítulo longo): null.
		boolean hasSample = chapters.stream().anyMatch(chapter -> Boolean.TRUE.equals(chapter.get("free")));
		out.put("freeChapterIndex", hasSample ? 1 : null);
		out.put("requiresSubscription", true);
		out.put("chapters", chapters);
		return out;
	}

	private Map<String, Object> progressMap(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("chapterIndex", number(row.get("chapter_index")));
		out.put("positionSeconds", number(row.get("position_seconds")));
		out.put("playbackRate", row.get("playback_rate"));
		out.put("completed", row.get("completed"));
		out.put("updatedAt", row.get("updated_at"));
		return out;
	}

	private Map<String, Object> requirePublishedEdition(UUID audioId) {
		Map<String, Object> edition = requireEdition(audioId);
		if (!"published".equals(existingString(edition, "status"))) {
			throw new AuthException(404, "Audiolivro não encontrado.");
		}
		return edition;
	}

	private Map<String, Object> requireBook(UUID bookId) {
		return books.findById(bookId).orElseThrow(() -> new AuthException(404, "Livro não encontrado."));
	}

	private Map<String, Object> requireEdition(UUID audioId) {
		return repository.findEdition(audioId)
				.orElseThrow(() -> new AuthException(404, "Audiolivro não encontrado."));
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

	private static String language(Map<String, Object> body, String fallback) {
		String value = text(body, "language");
		String selected = value == null ? fallback : value;
		if (!LANGUAGES.contains(selected)) throw new AuthException(400, "Idioma inválido.");
		return selected;
	}

	private static String source(Map<String, Object> body, String fallback) {
		String value = text(body, "source");
		String selected = value == null ? fallback : value;
		if (!SOURCES.contains(selected)) throw new AuthException(400, "Fonte inválida.");
		return selected;
	}

	private static String rights(Map<String, Object> body, String fallback) {
		String value = text(body, "rights");
		String selected = value == null ? fallback : value;
		if (selected == null || !RIGHTS.contains(selected)) throw new AuthException(400, "Direitos inválidos.");
		return selected;
	}

	private static String textMapping(Map<String, Object> body, String fallback) {
		String value = text(body, "textMapping");
		String selected = value == null ? fallback : value;
		if (selected == null || !TEXT_MAPPINGS.contains(selected)) {
			throw new AuthException(400, "Mapeamento de texto inválido.");
		}
		return selected;
	}

	private static void validateTextRange(Integer start, Integer end) {
		if (end != null && start == null) {
			throw new AuthException(400, "Informe o capítulo inicial do texto.");
		}
		if (start != null && start < 1) throw new AuthException(400, "Capítulo do texto inválido.");
		if (end != null && end < 1) throw new AuthException(400, "Capítulo final do texto inválido.");
		if (start != null && end != null && end < start) {
			throw new AuthException(400, "O fim do mapeamento é antes do início.");
		}
	}

	private static String extension(String fileName) {
		String name = fileName == null ? "" : fileName.strip().toLowerCase();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) throw new AuthException(400, "Informe um arquivo de áudio.");
		String extension = name.substring(dot);
		if (!AUDIO_TYPES.containsKey(extension.substring(1))) {
			throw new AuthException(415, "Formato de áudio não aceito.");
		}
		return extension;
	}

	private static String audioType(String contentType, String extension) {
		String type = contentType == null ? "" : contentType.strip().toLowerCase();
		if (AUDIO_TYPES.containsValue(type)) return type;
		return AUDIO_TYPES.get(extension.substring(1));
	}

	private static String mimeFor(String path) {
		String name = path == null ? "" : path.toLowerCase();
		int dot = name.lastIndexOf('.');
		if (dot >= 0) {
			String type = AUDIO_TYPES.get(name.substring(dot + 1));
			if (type != null) return type;
		}
		return "audio/mpeg";
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

	private static String existingString(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value instanceof String string ? string : null;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static UUID uuid(Object value) {
		if (value instanceof UUID uuid) return uuid;
		return UUID.fromString(String.valueOf(value));
	}

	/** Duração máxima da amostra pública (capítulo 1 sem login). */
	static final int SAMPLE_MAX_SECONDS = 45 * 60;

	/**
	 * O capítulo é amostra aberta (sem login nem assinatura)? Só o capítulo 1, só quando a edição tem
	 * mais de um capítulo e só se ele tiver duração conhecida de até {@link #SAMPLE_MAX_SECONDS}.
	 * Com um capítulo só, ele é o livro inteiro (S4 da revisão de 09/11/2026: 7 h abertas).
	 */
	static boolean freeSample(int chapterIndex, Object chapterCount, Object durationSeconds) {
		int duration = number(durationSeconds);
		return chapterIndex == 1 && number(chapterCount) > 1 && duration > 0 && duration <= SAMPLE_MAX_SECONDS;
	}

	private static int number(Object value) {
		if (value instanceof Number number) return number.intValue();
		if (value instanceof String string) {
			try {
				return Integer.parseInt(string.strip());
			} catch (NumberFormatException ignored) {
				return 0;
			}
		}
		return 0;
	}

	private static int integer(Map<String, Object> body, String key, int fallback) {
		Object value = body.get(key);
		if (value instanceof Number number) return number.intValue();
		if (value instanceof String string) {
			try {
				return Integer.parseInt(string.strip());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	private static double doubleValue(Map<String, Object> body, String key, double fallback) {
		Object value = body.get(key);
		if (value instanceof Number number) return number.doubleValue();
		if (value instanceof String string && !string.isBlank()) {
			try {
				return Double.parseDouble(string.strip());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	private static Integer optionalInteger(Object value) {
		if (value == null) return null;
		if (value instanceof Number number) return number.intValue();
		if (value instanceof String string && !string.isBlank()) {
			try {
				return Integer.parseInt(string.strip());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private static Long optionalLong(Object value) {
		if (value == null) return null;
		if (value instanceof Number number) return number.longValue();
		if (value instanceof String string && !string.isBlank()) {
			try {
				return Long.parseLong(string.strip());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private static long longValue(Map<String, Object> body, String key, long fallback) {
		Long value = optionalLong(body.get(key));
		return value == null ? fallback : value;
	}
}
