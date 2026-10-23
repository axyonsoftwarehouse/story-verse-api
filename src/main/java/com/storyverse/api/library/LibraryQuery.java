package com.storyverse.api.library;

import java.math.BigDecimal;
import java.util.List;

/**
 * Filtros de `GET /api/v1/me/library`. `status` aceita itens em CSV. Com `updatedSince` (um
 * `updatedAt` lido antes) vira a leitura do sync: só o que mudou depois, inclusive apagadas.
 */
public record LibraryQuery(
		List<String> status,
		String author,
		String series,
		String language,
		String genre,
		Integer year,
		BigDecimal minRating,
		BigDecimal maxRating,
		String sort,
		String cursor,
		Integer limit,
		String updatedSince) {
}
