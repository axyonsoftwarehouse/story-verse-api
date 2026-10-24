package com.storyverse.api.admin;

import java.time.LocalDate;

/** Um ponto da série de leitura (dia ou semana). */
public record AdminMetricsReadingPoint(LocalDate bucket, long activeReaders, long minutes,
		long booksStarted, long booksFinished) {
}
