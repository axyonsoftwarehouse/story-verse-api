package com.storyverse.api.admin;

/** Mascara e-mails nas listas do painel: {@code we***@gmail.com}. Nunca devolve o e-mail inteiro. */
public final class EmailMask {

	private static final String HIDDEN = "***";

	private EmailMask() {
	}

	public static String mask(String email) {
		if (email == null) {
			return HIDDEN;
		}
		int at = email.lastIndexOf('@');
		if (at <= 0) {
			return HIDDEN;
		}
		String local = email.substring(0, at);
		int visible = local.length() <= 2 ? 1 : 2;
		return local.substring(0, visible) + HIDDEN + email.substring(at);
	}
}
