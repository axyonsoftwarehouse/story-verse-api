package com.storyverse.api.common;

import org.springframework.dao.DataAccessException;

/** Extrai a mensagem amigável de um erro do Postgres (RAISE EXCEPTION), sem "ERROR:" nem "Where:". */
public final class DbErrors {

	private DbErrors() {
	}

	public static String message(DataAccessException exception) {
		Throwable cause = exception.getMostSpecificCause();
		String message = cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
		if (message == null) {
			return "Não foi possível concluir a ação.";
		}
		String cleaned = message.replaceFirst("^ERROR:\\s*", "").trim();
		int newline = cleaned.indexOf('\n');
		if (newline >= 0) {
			cleaned = cleaned.substring(0, newline).trim();
		}
		return cleaned.isEmpty() ? "Não foi possível concluir a ação." : cleaned;
	}
}
