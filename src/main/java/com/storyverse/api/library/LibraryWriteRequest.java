package com.storyverse.api.library;

import java.math.BigDecimal;

/**
 * Corpo do PUT: a entrada inteira. Datas em `yyyy-MM-dd`. `baseUpdatedAt` é o `updatedAt` lido
 * antes (null ao criar).
 */
public record LibraryWriteRequest(
		String source,
		String status,
		BigDecimal rating,
		String review,
		String startedOn,
		String finishedOn,
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
		String baseUpdatedAt) {
}
