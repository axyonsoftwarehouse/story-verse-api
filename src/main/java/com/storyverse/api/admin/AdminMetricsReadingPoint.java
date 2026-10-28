package com.storyverse.api.admin;

import java.time.LocalDate;

/**
 * Um ponto da série de leitura (dia ou semana). {@code signups}: contas criadas no bucket;
 * {@code pages}: páginas lidas, 1 página = 250 palavras (ver V26__reading_days_pages.sql).
 */
public record AdminMetricsReadingPoint(LocalDate bucket, long activeReaders, long minutes,
		long booksStarted, long booksFinished, long signups, long pages) {
}
