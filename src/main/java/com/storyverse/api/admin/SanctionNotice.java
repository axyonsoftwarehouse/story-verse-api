package com.storyverse.api.admin;

import java.time.Instant;

/** Aviso ativo devolvido no login (não bloqueia). */
public record SanctionNotice(String kind, String reason, Instant expiresAt) {

	public static SanctionNotice from(Sanction sanction) {
		return sanction == null ? null
				: new SanctionNotice(sanction.kind(), sanction.reason(), sanction.expiresAt());
	}
}
