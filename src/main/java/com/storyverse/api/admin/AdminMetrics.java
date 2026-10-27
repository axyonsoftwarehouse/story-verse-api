package com.storyverse.api.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Números agregados da fase 2C. Sem PII: contas/leitura/features/retenção. */
public record AdminMetrics(Accounts accounts, Reading reading, Map<String, Long> features,
		List<Retention> retention) {

	/** {@code new24h}: últimas 24 horas corridas; {@code newToday}: dia de hoje em São Paulo. */
	public record Accounts(long total, long new24h, long newToday, long new7, long new30, long confirmed,
			long staff) {
	}

	/** {@code pages}: páginas lidas, 1 página = 250 palavras (ver V26__reading_days_pages.sql). */
	public record Reading(long activeReaders, long minutes, long booksStarted, long booksFinished,
			long shelfEntries, long pages) {
	}

	public record Retention(LocalDate cohortWeek, long cohortSize, long d1, long d7, long d30) {
	}
}
