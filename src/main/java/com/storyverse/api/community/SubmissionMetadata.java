package com.storyverse.api.community;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Título e autor mínimos para um livro entrar (ou ficar) no acervo público (U1 da revisão de 09/11).
 * Vitrine com "Morte_DiaValePenaViver_FINAL.indd" ou autor "V" passa descuido. A mesma regra está no
 * site, em src/lib/submissionMetadata.ts: mudar as duas juntas.
 */
final class SubmissionMetadata {

	/** Extensões de arquivo de livro e de diagramação que já apareceram como título. */
	private static final Pattern FILE_EXTENSION = Pattern.compile(
			"\\.(indd|idml|pdf|epub|mobi|azw3?|docx?|odt|rtf|txt|fb2|html?|zip)$", Pattern.CASE_INSENSITIVE);

	private SubmissionMetadata() {
	}

	/** Primeiro problema que impede publicar, ou {@code null} se título e autor servem. */
	static String problem(String title, String author) {
		if (title == null || title.isBlank()) return "Informe o título do livro.";
		if (looksLikeFileName(title.trim())) {
			return "O título parece nome de arquivo (“" + title.trim() + "”). Corrija o título antes de publicar.";
		}
		if (!hasAuthor(author)) return "Informe o autor do livro.";
		return null;
	}

	static boolean looksLikeFileName(String title) {
		return FILE_EXTENSION.matcher(title).find() || title.contains("_") || title.contains("[") || title.contains("]");
	}

	private static boolean hasAuthor(String author) {
		if (author == null) return false;
		String trimmed = author.trim();
		if ("autor não informado".equals(trimmed.toLowerCase(Locale.ROOT))) return false;
		// Pelo menos duas letras: "V" ou "?" não identificam ninguém.
		return trimmed.codePoints().filter(Character::isLetter).count() >= 2;
	}
}
