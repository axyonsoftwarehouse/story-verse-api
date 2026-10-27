package com.storyverse.api.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import com.storyverse.api.auth.AuthException;

/** Período dos números do painel: datas de São Paulo, padrão de 30 dias e no máximo 90. */
public record AdminRange(LocalDate from, LocalDate to) {

	public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final int MAX_DAYS = 90;

	public static AdminRange parse(String fromRaw, String toRaw) {
		LocalDate to = parseDate(toRaw, LocalDate.now(SAO_PAULO));
		LocalDate from = parseDate(fromRaw, to.minusDays(29));
		if (from.isAfter(to)) {
			throw new AuthException(400, "A data inicial precisa ser anterior à final.");
		}
		if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw new AuthException(400, "O período máximo é de 90 dias.");
		}
		return new AdminRange(from, to);
	}

	/** {@code day} (padrão) ou {@code week}. */
	public static String group(String raw) {
		String bucket = raw == null || raw.isBlank() ? "day" : raw.strip();
		if (!bucket.equals("day") && !bucket.equals("week")) {
			throw new AuthException(400, "Agrupamento inválido. Use day ou week.");
		}
		return bucket;
	}

	private static LocalDate parseDate(String raw, LocalDate fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException error) {
			throw new AuthException(400, "Data inválida. Use AAAA-MM-DD.");
		}
	}
}
