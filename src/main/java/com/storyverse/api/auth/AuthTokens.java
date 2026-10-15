package com.storyverse.api.auth;

public final class AuthTokens {

	private AuthTokens() {
	}

	/** Extrai o token de "Authorization: Bearer <token>" (null se ausente/malformado). */
	public static String bearer(String authorization) {
		if (authorization == null) {
			return null;
		}
		String header = authorization.trim();
		if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return header.substring(7).trim();
		}
		return null;
	}
}
