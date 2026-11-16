package com.storyverse.api.catalog;

/**
 * V1 da revisão de 09/11/2026: o acervo público recebeu obras protegidas porque o campo de direitos
 * era só declarado. Para publicar (livro, áudio ou envio da comunidade), quem modera escreve de onde
 * vem o direito de publicar: fonte, ano de morte do autor ou licença.
 */
public final class RightsJustification {

	/** Curto o bastante para "Gutenberg; autor morreu em 1910", longo o bastante para barrar "ok". */
	public static final int MIN_LENGTH = 15;

	public static final String MESSAGE =
			"Explique por que esta obra pode ser publicada: fonte, ano de morte do autor ou licença.";

	private RightsJustification() {
	}

	/** Mensagem de erro, ou {@code null} quando a justificativa basta. */
	public static String problem(String note) {
		if (note == null || note.strip().length() < MIN_LENGTH) return MESSAGE;
		return null;
	}
}
