package com.storyverse.api.library;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.storyverse.api.auth.AuthException;

/**
 * Cursor opaco. `sort=recent` usa a chave (`updated_at`, `book_key`), estável mesmo com escritas no
 * meio da paginação; as outras ordenações usam a posição (`o:<n>`), já que a estante é limitada.
 */
record LibraryCursor(String updatedAt, String bookKey, Integer offset) {

	static LibraryCursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			if (raw.startsWith("o:")) {
				int offset = Integer.parseInt(raw.substring(2));
				if (offset < 0) {
					throw new IllegalArgumentException();
				}
				return new LibraryCursor(null, null, offset);
			}
			String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
			int separator = decoded.lastIndexOf('|');
			if (separator <= 0 || separator == decoded.length() - 1) {
				throw new IllegalArgumentException();
			}
			return new LibraryCursor(decoded.substring(0, separator), decoded.substring(separator + 1), null);
		} catch (RuntimeException error) {
			throw new AuthException(400, "Cursor inválido.");
		}
	}

	static LibraryCursor after(LibraryEntry last) {
		return new LibraryCursor(last.updatedAt(), last.bookKey(), null);
	}

	static LibraryCursor at(int offset) {
		return new LibraryCursor(null, null, offset);
	}

	boolean isOffset() {
		return offset != null;
	}

	String encode() {
		if (isOffset()) {
			return "o:" + offset;
		}
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((updatedAt + "|" + bookKey).getBytes(StandardCharsets.UTF_8));
	}
}
