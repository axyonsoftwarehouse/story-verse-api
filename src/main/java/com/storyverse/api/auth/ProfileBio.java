package com.storyverse.api.auth;

import java.util.regex.Pattern;

/** Biografia do perfil: texto curto, público para quem abre o perfil. */
public final class ProfileBio {

	/** Caracteres visíveis (emoji conta como um). */
	public static final int MAX_LENGTH = 160;
	public static final int MAX_LINES = 4;

	/** Caracteres de controle e invisíveis, menos a quebra de linha e o ZWJ dos emojis compostos. */
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\u200D]]");
	private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
	private static final Pattern LINE_EDGES = Pattern.compile(" *\\n *");
	private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

	private ProfileBio() {
	}

	/** Limpa espaços e caracteres invisíveis; erro 400 se passar do tamanho ou das linhas. */
	public static String normalize(String raw) {
		String text = raw.replace("\r\n", "\n").replace('\r', '\n');
		text = SPACES.matcher(text).replaceAll(" ");
		text = CONTROL.matcher(text).replaceAll("");
		text = LINE_EDGES.matcher(text).replaceAll("\n");
		text = BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
		if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
			throw new AuthException(400, "A biografia pode ter até " + MAX_LENGTH + " caracteres.");
		}
		if (text.lines().count() > MAX_LINES) {
			throw new AuthException(400, "A biografia pode ter até " + MAX_LINES + " linhas.");
		}
		return text;
	}
}
