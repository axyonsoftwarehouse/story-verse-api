package com.storyverse.api.community;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class CommunityRepository {

	private final JdbcClient jdbc;

	public CommunityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> listApproved() {
		return DbRows.list(jdbc,
				"select * from public.book_submissions where status = 'approved' order by reviewed_at desc nulls last",
				Map.of());
	}

	public List<Map<String, Object>> listMine(UUID userId) {
		return DbRows.list(jdbc,
				"select * from public.book_submissions where user_id = :uid order by created_at desc",
				Map.of("uid", userId));
	}

	public List<Map<String, Object>> listPending() {
		return DbRows.list(jdbc,
				"select * from public.book_submissions where status = 'pending' order by created_at asc",
				Map.of());
	}

	public List<Map<String, Object>> listPendingFor(UUID userId) {
		return DbRows.list(jdbc,
				"select * from public.book_submissions where status = 'pending' and user_id = :uid order by created_at asc",
				Map.of("uid", userId));
	}

	public Optional<Map<String, Object>> find(UUID id) {
		return DbRows.one(jdbc, "select * from public.book_submissions where id = :id", Map.of("id", id));
	}

	public Optional<Map<String, Object>> findForUpdate(UUID id) {
		return DbRows.one(jdbc, "select * from public.book_submissions where id = :id for update", Map.of("id", id));
	}

	public Optional<Map<String, Object>> insert(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.book_submissions
					(user_id, submitter_name, title, author, language, rights, rights_note, characters, text_path, char_count, cover_url)
				values
					(:uid, :submitter_name, :title, :author, :language, :rights, :rights_note, :characters, :text_path, :char_count, :cover_url)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> review(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.book_submissions
				   set status = :status,
				       review_note = :note,
				       reviewed_at = now(),
				       cover_url = coalesce(:cover, cover_url)
				 where id = :id
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> updateCover(UUID id, String coverUrl) {
		return DbRows.one(jdbc,
				"update public.book_submissions set cover_url = :cover where id = :id returning *",
				Map.of("id", id, "cover", coverUrl));
	}

	public int delete(UUID id) {
		return DbRows.update(jdbc, "delete from public.book_submissions where id = :id", Map.of("id", id));
	}
}
