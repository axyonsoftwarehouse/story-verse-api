package com.storyverse.api.library;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.feature.FeatureAccessService;

import tools.jackson.databind.JsonNode;

/** Estante da conta (`/api/v1/me/library`). Toda rota exige a feature `library`. */
@Service
public class LibraryService {

	static final String FEATURE = "library";
	static final int MAX_ENTRIES = 5000;
	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 100;
	/** Colunas de ordenação permitidas; o cliente escolhe só a chave. */
	private static final Map<String, String> ORDER = Map.of(
			"recent", "updated_at desc, book_key desc",
			"title", "lower(title), book_key",
			"author", "lower(author) nulls last, book_key",
			"rating", "rating desc nulls last, book_key",
			"finished", "finished_on desc nulls last, book_key");
	/** Texto de `updated_at` como o Postgres devolve (o mesmo que a API entrega em `updatedAt`). */
	private static final Pattern TIMESTAMP = Pattern.compile(
			"^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?([+-]\\d{2}(:?\\d{2})?|Z)?$");

	private final LibraryRepository repository;
	private final AuthService auth;
	private final FeatureAccessService features;
	private final RateLimitGate limits;
	private final JdbcClient jdbc;

	public LibraryService(LibraryRepository repository, AuthService auth, FeatureAccessService features,
			RateLimitGate limits, JdbcClient jdbc) {
		this.repository = repository;
		this.auth = auth;
		this.features = features;
		this.limits = limits;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public LibraryEntry get(String accessToken, String bookKey) {
		UUID userId = open(accessToken);
		return live(userId, BookKey.parse(bookKey).value());
	}

	@Transactional
	public LibraryEntry put(String accessToken, String bookKey, LibraryWriteRequest request) {
		UUID userId = openForWrite(accessToken);
		BookKey key = BookKey.parse(bookKey);
		if (request == null) {
			throw new AuthException(400, "Corpo inválido.");
		}
		LibraryEntry candidate = LibraryRules.normalize(new LibraryEntry(key.value(), request.source(),
				request.status(), request.rating(), request.review(), date(request.startedOn()),
				date(request.finishedOn()), request.dnfReason(), request.dnfNote(), request.progressPercent(),
				request.progressLabel(), request.title(), request.author(), request.coverUrl(), request.series(),
				request.seriesIndex(), request.genre(), request.language(), null, 1, null));
		LibraryRules.validate(candidate);

		Optional<LibraryEntry> existing = repository.find(userId, key.value());
		if (existing.isEmpty()) {
			if (repository.countLive(userId) >= MAX_ENTRIES) {
				throw new AuthException(422, "Estante cheia.");
			}
			return repository.insert(userId, candidate).orElseThrow(() -> conflict(userId, key.value()));
		}
		LibraryEntry current = existing.get();
		if (current.deletedAt() != null) {
			return repository.revive(userId, candidate).orElseThrow(() -> conflict(userId, key.value()));
		}
		if (request.baseUpdatedAt() == null) {
			// Repetição do mesmo PUT (rede instável) continua idempotente; conteúdo diferente é conflito.
			if (sameContent(current, candidate)) {
				return current;
			}
			throw new LibraryConflictException(current);
		}
		return repository.replaceIfMatches(userId, candidate, request.baseUpdatedAt())
				.orElseThrow(() -> conflict(userId, key.value()));
	}

	@Transactional(readOnly = true)
	public LibraryPage list(String accessToken, LibraryQuery query) {
		UUID userId = open(accessToken);
		LibraryQuery q = query == null
				? new LibraryQuery(null, null, null, null, null, null, null, null, null, null, null, null)
				: query;
		// O sync lê tudo o que mudou, em ordem de gravação; os filtros de tela não se aplicam.
		String sort = q.updatedSince() != null || q.sort() == null ? "recent" : q.sort();
		String orderBy = ORDER.get(sort);
		if (orderBy == null) {
			throw new AuthException(400, "Ordenação inválida.");
		}
		int limit = q.limit() == null ? DEFAULT_LIMIT : Math.min(q.limit(), MAX_LIMIT);
		if (limit < 1) {
			throw new AuthException(400, "Limite inválido.");
		}
		List<String> statuses = new ArrayList<>();
		for (String item : q.status() == null ? List.<String>of() : q.status()) {
			for (String status : item.split(",")) {
				String trimmed = status.trim();
				if (!LibraryRules.STATUSES.contains(trimmed)) {
					throw new AuthException(400, "Status inválido.");
				}
				statuses.add(trimmed);
			}
		}
		if (q.updatedSince() != null && !TIMESTAMP.matcher(q.updatedSince()).matches()) {
			throw new AuthException(400, "updatedSince inválido.");
		}
		LibraryCursor cursor = LibraryCursor.decode(q.cursor());
		if (cursor != null && (cursor.isOffset() == "recent".equals(sort)
				|| (!cursor.isOffset() && !TIMESTAMP.matcher(cursor.updatedAt()).matches()))) {
			throw new AuthException(400, "Cursor inválido.");
		}
		List<LibraryEntry> rows = repository.list(userId, q, statuses, orderBy, cursor, limit + 1);
		if (rows.size() <= limit) {
			return new LibraryPage(rows, null);
		}
		List<LibraryEntry> items = rows.subList(0, limit);
		LibraryCursor next = "recent".equals(sort)
				? LibraryCursor.after(items.get(limit - 1))
				: LibraryCursor.at((cursor == null ? 0 : cursor.offset()) + limit);
		return new LibraryPage(List.copyOf(items), next.encode());
	}

	/** Números do ano (padrão: o ano corrente em Brasília). */
	@Transactional(readOnly = true)
	public LibraryStats stats(String accessToken, Integer year) {
		UUID userId = open(accessToken);
		int current = LibraryRules.today().getYear();
		int target = year == null ? current : year;
		if (target < 2000 || target > current + 1) {
			throw new AuthException(400, "Ano inválido.");
		}
		return repository.stats(userId, target);
	}

	/** Muda só os campos presentes no corpo (null limpa). Exige `baseUpdatedAt`. */
	@Transactional
	public LibraryEntry patch(String accessToken, String bookKey, JsonNode body) {
		UUID userId = openForWrite(accessToken);
		String key = BookKey.parse(bookKey).value();
		if (body == null || !body.isObject()) {
			throw new AuthException(400, "Corpo inválido.");
		}
		JsonNode base = body.get("baseUpdatedAt");
		if (base == null || !base.isString()) {
			throw new AuthException(400, "Informe baseUpdatedAt.");
		}
		Draft draft = new Draft(live(userId, key));
		for (Map.Entry<String, JsonNode> field : body.properties()) {
			draft.apply(field.getKey(), field.getValue());
		}
		LibraryEntry candidate = LibraryRules.normalize(draft.toEntry());
		LibraryRules.validate(candidate);
		return repository.replaceIfMatches(userId, candidate, base.asText())
				.orElseThrow(() -> conflict(userId, key));
	}

	/** Remove da estante (soft delete). Repetir é inofensivo; livro que nunca esteve lá é 404. */
	@Transactional
	public void delete(String accessToken, String bookKey) {
		UUID userId = openForWrite(accessToken);
		String key = BookKey.parse(bookKey).value();
		LibraryEntry current = repository.find(userId, key)
				.orElseThrow(() -> new AuthException(404, "Livro fora da estante."));
		if (current.deletedAt() == null) {
			repository.softDelete(userId, key);
		}
	}

	/** Concluir. Idempotente; nota e resenha nulas mantêm as atuais. */
	@Transactional
	public LibraryEntry finish(String accessToken, String bookKey, FinishRequest request) {
		UUID userId = openForWrite(accessToken);
		String key = BookKey.parse(bookKey).value();
		LibraryEntry current = lockLive(userId, key);
		FinishRequest r = request == null ? new FinishRequest(null, null, null) : request;
		Draft draft = new Draft(current);
		draft.status = "finished";
		draft.finishedOn = r.finishedOn() == null ? LibraryRules.today() : date(r.finishedOn());
		if (draft.startedOn != null && draft.startedOn.isAfter(draft.finishedOn)) {
			draft.startedOn = draft.finishedOn;
		}
		if (r.rating() != null) {
			draft.rating = r.rating();
		}
		if (r.review() != null) {
			draft.review = r.review();
		}
		return save(userId, current, draft);
	}

	@Transactional
	public LibraryEntry abandon(String accessToken, String bookKey, AbandonRequest request) {
		UUID userId = openForWrite(accessToken);
		String key = BookKey.parse(bookKey).value();
		LibraryEntry current = lockLive(userId, key);
		Draft draft = new Draft(current);
		draft.status = "abandoned";
		draft.dnfReason = request == null ? null : request.dnfReason();
		draft.dnfNote = request == null ? null : request.dnfNote();
		return save(userId, current, draft);
	}

	private LibraryEntry lockLive(UUID userId, String key) {
		return repository.findLiveForUpdate(userId, key)
				.orElseThrow(() -> new AuthException(404, "Livro fora da estante."));
	}

	private LibraryEntry save(UUID userId, LibraryEntry current, Draft draft) {
		LibraryEntry candidate = LibraryRules.normalize(draft.toEntry());
		LibraryRules.validate(candidate);
		return repository.replaceIfMatches(userId, candidate, current.updatedAt())
				.orElseThrow(() -> conflict(userId, current.bookKey()));
	}

	/** Autentica, exige a feature e marca a sessão do banco (RLS). */
	private UUID open(String accessToken) {
		UUID userId = auth.authenticate(accessToken);
		features.require(userId, FEATURE);
		DbSession.setUser(jdbc, userId);
		return userId;
	}

	private UUID openForWrite(String accessToken) {
		UUID userId = open(accessToken);
		limits.check("library-write:" + userId, 60, Duration.ofMinutes(1));
		return userId;
	}

	private LibraryEntry live(UUID userId, String bookKey) {
		return repository.find(userId, bookKey)
				.filter(entry -> entry.deletedAt() == null)
				.orElseThrow(() -> new AuthException(404, "Livro fora da estante."));
	}

	private LibraryConflictException conflict(UUID userId, String bookKey) {
		return new LibraryConflictException(repository.find(userId, bookKey).orElse(null));
	}

	static LocalDate date(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException exception) {
			throw new AuthException(400, "Data inválida.");
		}
	}

	/** Mesmo conteúdo, ignorando versão e datas de gravação (números comparados pelo valor). */
	static boolean sameContent(LibraryEntry a, LibraryEntry b) {
		return Objects.equals(a.source(), b.source()) && Objects.equals(a.status(), b.status())
				&& sameNumber(a.rating(), b.rating()) && Objects.equals(a.review(), b.review())
				&& Objects.equals(a.startedOn(), b.startedOn()) && Objects.equals(a.finishedOn(), b.finishedOn())
				&& Objects.equals(a.dnfReason(), b.dnfReason()) && Objects.equals(a.dnfNote(), b.dnfNote())
				&& Objects.equals(a.progressPercent(), b.progressPercent())
				&& Objects.equals(a.progressLabel(), b.progressLabel()) && Objects.equals(a.title(), b.title())
				&& Objects.equals(a.author(), b.author()) && Objects.equals(a.coverUrl(), b.coverUrl())
				&& Objects.equals(a.series(), b.series()) && sameNumber(a.seriesIndex(), b.seriesIndex())
				&& Objects.equals(a.genre(), b.genre()) && Objects.equals(a.language(), b.language());
	}

	private static boolean sameNumber(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	/** Cópia editável de uma entrada, para PATCH, concluir e abandonar. */
	private static final class Draft {
		final String bookKey;
		final String source;
		String status;
		BigDecimal rating;
		String review;
		LocalDate startedOn;
		LocalDate finishedOn;
		String dnfReason;
		String dnfNote;
		Integer progressPercent;
		String progressLabel;
		String title;
		String author;
		String coverUrl;
		String series;
		BigDecimal seriesIndex;
		String genre;
		String language;

		Draft(LibraryEntry e) {
			bookKey = e.bookKey();
			source = e.source();
			status = e.status();
			rating = e.rating();
			review = e.review();
			startedOn = e.startedOn();
			finishedOn = e.finishedOn();
			dnfReason = e.dnfReason();
			dnfNote = e.dnfNote();
			progressPercent = e.progressPercent();
			progressLabel = e.progressLabel();
			title = e.title();
			author = e.author();
			coverUrl = e.coverUrl();
			series = e.series();
			seriesIndex = e.seriesIndex();
			genre = e.genre();
			language = e.language();
		}

		void apply(String field, JsonNode value) {
			switch (field) {
				case "baseUpdatedAt" -> { }
				case "status" -> status = text(value);
				case "rating" -> rating = number(value);
				case "review" -> review = text(value);
				case "startedOn" -> startedOn = date(text(value));
				case "finishedOn" -> finishedOn = date(text(value));
				case "dnfReason" -> dnfReason = text(value);
				case "dnfNote" -> dnfNote = text(value);
				case "progressPercent" -> progressPercent = number(value) == null ? null : number(value).intValue();
				case "progressLabel" -> progressLabel = text(value);
				case "title" -> title = text(value);
				case "author" -> author = text(value);
				case "coverUrl" -> coverUrl = text(value);
				case "series" -> series = text(value);
				case "seriesIndex" -> seriesIndex = number(value);
				case "genre" -> genre = text(value);
				case "language" -> language = text(value);
				case "source", "bookKey" -> throw new AuthException(400, "Campo não pode ser alterado.");
				default -> throw new AuthException(400, "Campo desconhecido: " + field + ".");
			}
		}

		LibraryEntry toEntry() {
			return new LibraryEntry(bookKey, source, status, rating, review, startedOn, finishedOn, dnfReason,
					dnfNote, progressPercent, progressLabel, title, author, coverUrl, series, seriesIndex, genre,
					language, null, 0, null);
		}

		private static String text(JsonNode value) {
			if (value.isNull()) {
				return null;
			}
			if (!value.isString()) {
				throw new AuthException(400, "Campo de texto inválido.");
			}
			return value.asText();
		}

		private static BigDecimal number(JsonNode value) {
			if (value.isNull()) {
				return null;
			}
			if (!value.isNumber()) {
				throw new AuthException(400, "Campo numérico inválido.");
			}
			return value.decimalValue();
		}
	}
}
