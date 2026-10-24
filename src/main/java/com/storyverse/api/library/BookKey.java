package com.storyverse.api.library;

import java.util.regex.Pattern;

import com.storyverse.api.auth.AuthException;

/** Chave estável do livro na estante: `gb:<n>`, `cm:<uuid>`, `cat:<uuid>` ou `local:<id>`. */
public record BookKey(String value, String source) {

	private static final Pattern FORMAT = Pattern.compile(
			"^(gb:[0-9]+|cm:[0-9a-fA-F-]{36}|cat:[0-9a-fA-F-]{36}|local:-?[0-9]+)$");

	public static BookKey parse(String raw) {
		if (raw == null || !FORMAT.matcher(raw).matches()) {
			throw new AuthException(400, "Livro inválido.");
		}
		String source = raw.startsWith("gb:") ? "gutenberg"
				: raw.startsWith("cm:") ? "community"
						: raw.startsWith("cat:") ? "catalog" : "local";
		return new BookKey(raw, source);
	}
}
