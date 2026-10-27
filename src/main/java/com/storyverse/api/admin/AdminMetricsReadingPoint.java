package com.storyverse.api.admin;

import java.time.LocalDate;

/** Um ponto da série de leitura (dia ou semana). {@code signups}: contas criadas no bucket. */
public record AdminMetricsReadingPoint(LocalDate bucket, long activeReaders, long minutes,
		long booksStarted, long booksFinished, long signups) {
}
