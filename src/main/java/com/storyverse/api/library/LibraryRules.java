package com.storyverse.api.library;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import com.storyverse.api.auth.AuthException;

/** Regras da entrada final da estante. Toda escrita passa por normalize + validate antes de gravar. */
final class LibraryRules {

	static final Set<String> STATUSES = Set.of("want_to_read", "reading", "finished", "abandoned");
	static final Set<String> DNF_REASONS = Set.of("slow", "not_for_me", "difficult", "other");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	private static final BigDecimal MIN_RATING = new BigDecimal("0.5");
	private static final BigDecimal MAX_RATING = new BigDecimal("5");

	private LibraryRules() {
	}

	/** "Hoje" para quem lê o Storyverse (horário de Brasília). */
	static LocalDate today() {
		return LocalDate.now(ZONE);
	}

	/** Ajustes que o servidor faz sozinho: data e progresso ao concluir, motivo apagado ao retomar. */
	static LibraryEntry normalize(LibraryEntry e) {
		boolean finished = "finished".equals(e.status());
		boolean abandoned = "abandoned".equals(e.status());
		return new LibraryEntry(e.bookKey(), e.source(), e.status(), e.rating(), e.review(), e.startedOn(),
				finished && e.finishedOn() == null ? today() : e.finishedOn(),
				abandoned ? e.dnfReason() : null,
				abandoned ? e.dnfNote() : null,
				finished ? Integer.valueOf(100) : e.progressPercent(),
				e.progressLabel(), e.title(), e.author(), e.coverUrl(), e.series(), e.seriesIndex(), e.genre(),
				e.language(), e.updatedAt(), e.version(), e.deletedAt());
	}

	static void validate(LibraryEntry e) {
		if (!BookKey.parse(e.bookKey()).source().equals(e.source())) {
			throw new AuthException(400, "Origem não confere com o livro.");
		}
		if (e.status() == null || !STATUSES.contains(e.status())) {
			throw new AuthException(400, "Status inválido.");
		}
		if (e.title() == null || e.title().isBlank()) {
			throw new AuthException(400, "Informe o título.");
		}
		maxLength(e.title(), 300, "Título");
		maxLength(e.review(), 4000, "Resenha");
		maxLength(e.dnfNote(), 500, "Nota do abandono");
		maxLength(e.progressLabel(), 200, "Rótulo do progresso");
		maxLength(e.author(), 300, "Autor");
		maxLength(e.coverUrl(), 500, "Endereço da capa");
		maxLength(e.series(), 200, "Série");
		maxLength(e.genre(), 80, "Gênero");
		if (e.progressPercent() != null && (e.progressPercent() < 0 || e.progressPercent() > 100)) {
			throw new AuthException(400, "Progresso inválido.");
		}
		if (e.language() != null && !e.language().equals("pt") && !e.language().equals("en")) {
			throw new AuthException(400, "Idioma inválido.");
		}
		if (e.dnfReason() != null && !DNF_REASONS.contains(e.dnfReason())) {
			throw new AuthException(400, "Motivo inválido.");
		}
		if (e.rating() != null) {
			BigDecimal doubled = e.rating().multiply(BigDecimal.TWO);
			if (e.rating().compareTo(MIN_RATING) < 0 || e.rating().compareTo(MAX_RATING) > 0
					|| doubled.stripTrailingZeros().scale() > 0) {
				throw new AuthException(422, "Nota inválida.");
			}
			if (e.finishedOn() == null) {
				throw new AuthException(422, "Só livros terminados recebem nota.");
			}
		}
		if ("abandoned".equals(e.status()) && e.dnfReason() == null) {
			throw new AuthException(422, "Informe o motivo do abandono.");
		}
		LocalDate limit = today().plusDays(1);
		if ((e.startedOn() != null && e.startedOn().isAfter(limit))
				|| (e.finishedOn() != null && e.finishedOn().isAfter(limit))) {
			throw new AuthException(422, "Data no futuro.");
		}
		if (e.startedOn() != null && e.finishedOn() != null && e.startedOn().isAfter(e.finishedOn())) {
			throw new AuthException(422, "A data de início é depois da conclusão.");
		}
	}

	private static void maxLength(String value, int max, String field) {
		if (value != null && value.length() > max) {
			throw new AuthException(400, field + " grande demais.");
		}
	}
}
