package com.storyverse.api.reading;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReadingStateRepository {

	private final JdbcClient jdbc;

	public ReadingStateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<StateRow> find(UUID userId) {
		return jdbc.sql("""
				select data::text as data, updated_at::text as updated_at
				from public.reading_state
				where user_id = :userId
				""")
				.param("userId", userId)
				.query((rs, rowNum) -> new StateRow(rs.getString("data"), rs.getString("updated_at")))
				.optional();
	}

	/** Primeira gravação da conta. Vazio se outro aparelho criou a linha antes. */
	public Optional<String> insertIfAbsent(UUID userId, String dataJson) {
		return jdbc.sql("""
				insert into public.reading_state (user_id, data, updated_at)
				values (:userId, cast(:data as jsonb), now())
				on conflict (user_id) do nothing
				returning updated_at::text
				""")
				.param("userId", userId)
				.param("data", dataJson)
				.query(String.class)
				.optional();
	}

	/** Grava só se a nuvem ainda estiver como o cliente leu (`baseUpdatedAt`). Vazio se mudou. */
	public Optional<String> updateIfMatches(UUID userId, String dataJson, String baseUpdatedAt) {
		return jdbc.sql("""
				update public.reading_state
				   set data = cast(:data as jsonb), updated_at = now()
				 where user_id = :userId and updated_at = cast(:base as timestamptz)
				 returning updated_at::text
				""")
				.param("userId", userId)
				.param("data", dataJson)
				.param("base", baseUpdatedAt)
				.query(String.class)
				.optional();
	}
}
