package com.storyverse.api.library;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Uma linha da estante, como a API devolve. `updatedAt` é o texto de `updated_at` (mesmo formato
 * que o cliente manda de volta em `baseUpdatedAt`); `deletedAt` só aparece para o sync.
 */
public record LibraryEntry(
		String bookKey,
		String source,
		String status,
		BigDecimal rating,
		String review,
		LocalDate startedOn,
		LocalDate finishedOn,
		String dnfReason,
		String dnfNote,
		Integer progressPercent,
		String progressLabel,
		String title,
		String author,
		String coverUrl,
		String series,
		BigDecimal seriesIndex,
		String genre,
		String language,
		String updatedAt,
		int version,
		String deletedAt) {
}
