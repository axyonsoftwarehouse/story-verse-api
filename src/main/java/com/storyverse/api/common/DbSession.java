package com.storyverse.api.common;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** O "tradutor": informa ao banco qual usuário está na transação (para o `auth.uid()`). */
public final class DbSession {

	private DbSession() {
	}

	public static void setUser(JdbcClient jdbc, UUID userId) {
		jdbc.sql("select set_config('app.user_id', :uid, true)")
				.param("uid", userId.toString())
				.query((rs, rowNum) -> null)
				.optional();
	}
}
