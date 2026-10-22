package com.storyverse.api.library;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.feature.FeatureAccessService;

/** Estante da conta (`/api/v1/me/library`). Toda rota exige a feature `library`. */
@Service
public class LibraryService {

	static final String FEATURE = "library";
	static final int MAX_ENTRIES = 5000;

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
}
