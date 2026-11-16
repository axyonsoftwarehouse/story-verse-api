package com.storyverse.api.auth;

import java.util.Set;


/** Temas visuais permitidos no perfil público (auth.users.raw_user_meta_data.profile_theme). */
public final class ProfileThemes {
	public static final String DEFAULT = "dourado";
	private static final Set<String> ALLOWED = Set.of("dourado", "esmeralda", "rosa", "oceano", "lavanda");

	private ProfileThemes() {
	}

	public static String normalizeStored(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT;
		}
		String id = raw.trim().toLowerCase();
		return ALLOWED.contains(id) ? id : DEFAULT;
	}

	/** {@code null}: não altera; vazio ou dourado: remove do metadata; outro valor: grava após validar. */
	public static void putIfPresent(java.util.Map<String, Object> metadata, String profileTheme) {
		if (profileTheme == null) {
			return;
		}
		String id = profileTheme.trim().toLowerCase();
		if (id.isEmpty() || DEFAULT.equals(id)) {
			metadata.remove("profile_theme");
			return;
		}
		if (!ALLOWED.contains(id)) {
			throw new AuthException(400, "Tema de perfil inválido.");
		}
		metadata.put("profile_theme", id);
	}
}