package com.storyverse.api.forum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class ForumRepository {

	private static final UUID ANON = new UUID(0L, 0L);

	private static final String TOPIC_LIST = """
			select t.id, t.space_id, s.slug as space_slug, s.name as space_name,
			       t.author_id, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name,
			       coalesce(u.raw_user_meta_data->>'avatar', '') as author_avatar,
			       t.title, left(t.body, 320) as excerpt, t.book_key, t.chapter_ref,
			       t.spoiler_level, t.status, t.is_pinned, t.is_locked, t.accepted_reply_id,
			       t.reply_count, t.reaction_count, t.last_activity_at, t.created_at, t.updated_at,
			       exists(select 1 from public.forum_bookmarks b
			               where b.topic_id = t.id and b.user_id = :viewer) as bookmarked,
			       exists(select 1 from public.forum_reactions r
			               where r.target_type = 'topic' and r.target_id = t.id and r.user_id = :viewer) as liked
			  from public.forum_topics t
			  join public.forum_spaces s on s.id = t.space_id
			  join auth.users u on u.id = t.author_id
			""";

	private static final String TOPIC_DETAIL = TOPIC_LIST.replace("left(t.body, 320) as excerpt", "t.body as body");

	private static final String REPLY_LIST = """
			select r.id, r.topic_id, r.author_id, r.parent_id, r.body, r.status, r.spoiler_level, r.reaction_count,
			       r.created_at, r.updated_at,
			       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name,
			       coalesce(u.raw_user_meta_data->>'avatar', '') as author_avatar,
			       exists(select 1 from public.forum_reactions x
			               where x.target_type = 'reply' and x.target_id = r.id and x.user_id = :viewer) as liked
			  from public.forum_replies r
			  join auth.users u on u.id = r.author_id
			""";

	private final JdbcClient jdbc;

	public ForumRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> spaces() {
		return DbRows.list(jdbc, """
				select s.id, s.slug, s.name, s.description, s.sort_order,
				       (select count(*) from public.forum_topics t
				         where t.space_id = s.id and t.status = 'published' and t.deleted_at is null) as topic_count,
				       (select max(t.last_activity_at) from public.forum_topics t
				         where t.space_id = s.id and t.status = 'published' and t.deleted_at is null) as last_activity_at
				  from public.forum_spaces s
				 where s.is_active
				 order by s.sort_order, s.name
				""", Map.of());
	}

	public Optional<Map<String, Object>> spaceBySlug(String slug) {
		return DbRows.one(jdbc,
				"select id, slug, name, description, is_active from public.forum_spaces where slug = :slug",
				Map.of("slug", slug));
	}

	public List<Map<String, Object>> topics(UUID viewer, String space, String bookKey, String filter,
			String query, ForumCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder(TOPIC_LIST)
				.append(" where t.status = 'published' and t.deleted_at is null");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("viewer", viewer == null ? ANON : viewer);

		if (space != null && !space.isBlank()) {
			sql.append(" and s.slug = :space");
			params.put("space", space);
		}
		if (bookKey != null && !bookKey.isBlank()) {
			sql.append(" and t.book_key = :bookKey");
			params.put("bookKey", bookKey);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" and (t.title ilike :query or t.body ilike :query)");
			params.put("query", "%" + query.trim() + "%");
		}
		if ("unanswered".equals(filter)) {
			sql.append(" and t.reply_count = 0");
		}
		if (cursor != null && !"popular".equals(filter)) {
			sql.append("""
					 and (t.last_activity_at < :cursorTime
					      or (t.last_activity_at = :cursorTime and t.id < :cursorId))
					""");
			params.put("cursorTime", cursor.time());
			params.put("cursorId", cursor.id());
		}
		if ("popular".equals(filter)) {
			sql.append(" order by (t.reaction_count + t.reply_count * 2) desc, t.last_activity_at desc, t.id desc");
		} else {
			sql.append(" order by t.last_activity_at desc, t.id desc");
		}
		sql.append(" limit :limit");
		params.put("limit", limit);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> topic(UUID viewer, UUID id) {
		return DbRows.one(jdbc, TOPIC_DETAIL + " where t.id = :id",
				Map.of("viewer", viewer == null ? ANON : viewer, "id", id));
	}

	public List<Map<String, Object>> replies(UUID viewer, UUID topicId, ForumCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder(REPLY_LIST)
				.append("""
						 where r.topic_id = :topicId
						   and r.status = 'published' and r.deleted_at is null
						   and public.forum_topic_visible(r.topic_id)
						""");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("viewer", viewer == null ? ANON : viewer);
		params.put("topicId", topicId);
		if (cursor != null) {
			sql.append("""
					 and (r.created_at > :cursorTime
					      or (r.created_at = :cursorTime and r.id > :cursorId))
					""");
			params.put("cursorTime", cursor.time());
			params.put("cursorId", cursor.id());
		}
		sql.append(" order by r.created_at asc, r.id asc limit :limit");
		params.put("limit", limit);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> reply(UUID viewer, UUID id) {
		return DbRows.one(jdbc, REPLY_LIST + " where r.id = :id",
				Map.of("viewer", viewer == null ? ANON : viewer, "id", id));
	}

	public Optional<Map<String, Object>> topicState(UUID id) {
		return DbRows.one(jdbc, """
				select id, author_id, status, is_locked, created_at, deleted_at
				  from public.forum_topics where id = :id
				""", Map.of("id", id));
	}

	public Optional<Map<String, Object>> replyState(UUID id) {
		return DbRows.one(jdbc, """
				select id, topic_id, author_id, status, created_at, deleted_at
				  from public.forum_replies where id = :id
				""", Map.of("id", id));
	}

	public UUID insertTopic(UUID spaceId, UUID authorId, String title, String body, String bookKey,
			String chapterRef, String spoilerLevel, String status) {
		return jdbc.sql("""
				insert into public.forum_topics
					(space_id, author_id, title, body, book_key, chapter_ref, spoiler_level, status)
				values
					(:spaceId, :authorId, :title, :body, :bookKey, :chapterRef, :spoilerLevel, :status)
				returning id
				""")
				.param("spaceId", spaceId)
				.param("authorId", authorId)
				.param("title", title)
				.param("body", body)
				.param("bookKey", bookKey)
				.param("chapterRef", chapterRef)
				.param("spoilerLevel", spoilerLevel)
				.param("status", status)
				.query((rs, rowNum) -> rs.getObject("id", UUID.class))
				.single();
	}

	public UUID insertReply(UUID topicId, UUID authorId, UUID parentId, String body, String status,
			String spoilerLevel) {
		return jdbc.sql("""
				insert into public.forum_replies (topic_id, author_id, parent_id, body, status, spoiler_level)
				values (:topicId, :authorId, :parentId, :body, :status, :spoilerLevel)
				returning id
				""")
				.param("topicId", topicId)
				.param("authorId", authorId)
				.param("parentId", parentId)
				.param("body", body)
				.param("status", status)
				.param("spoilerLevel", spoilerLevel == null ? "none" : spoilerLevel)
				.query((rs, rowNum) -> rs.getObject("id", UUID.class))
				.single();
	}

	public void updateTopicContent(UUID id, String title, String body, String bookKey, String chapterRef,
			String spoilerLevel) {
		jdbc.sql("""
				update public.forum_topics
				   set title = coalesce(cast(:title as text), title),
				       body = coalesce(cast(:body as text), body),
				       book_key = coalesce(cast(:bookKey as text), book_key),
				       chapter_ref = coalesce(cast(:chapterRef as text), chapter_ref),
				       spoiler_level = coalesce(cast(:spoilerLevel as text), spoiler_level),
				       updated_at = now()
				 where id = :id
				""")
				.param("id", id)
				.param("title", title)
				.param("body", body)
				.param("bookKey", bookKey)
				.param("chapterRef", chapterRef)
				.param("spoilerLevel", spoilerLevel)
				.update();
	}

	public void updateReplyContent(UUID id, String body) {
		jdbc.sql("update public.forum_replies set body = :body, updated_at = now() where id = :id")
				.param("id", id).param("body", body).update();
	}

	public void softDeleteTopic(UUID id) {
		jdbc.sql("""
				update public.forum_topics
				   set status = 'deleted', deleted_at = now(), updated_at = now()
				 where id = :id
				""").param("id", id).update();
	}

	public void softDeleteReply(UUID id) {
		jdbc.sql("""
				update public.forum_replies
				   set status = 'deleted', deleted_at = now(), updated_at = now()
				 where id = :id
				""").param("id", id).update();
	}

	public void moderateTopic(UUID id, String status, Boolean pinned, Boolean locked) {
		jdbc.sql("""
				update public.forum_topics
				   set status = coalesce(cast(:status as text), status),
				       is_pinned = coalesce(cast(:pinned as boolean), is_pinned),
				       is_locked = coalesce(cast(:locked as boolean), is_locked),
				       updated_at = now()
				 where id = :id
				""")
				.param("id", id).param("status", status).param("pinned", pinned).param("locked", locked)
				.update();
	}

	public void moderateReply(UUID id, String status) {
		jdbc.sql("""
				update public.forum_replies
				   set status = coalesce(cast(:status as text), status), updated_at = now()
				 where id = :id
				""").param("id", id).param("status", status).update();
	}

	public void acceptReply(UUID topicId, UUID replyId, UUID actorId) {
		jdbc.sql("""
				update public.forum_topics
				   set accepted_reply_id = :replyId, accepted_by = :actorId,
				       accepted_at = now(), updated_at = now()
				 where id = :topicId
				""").param("topicId", topicId).param("replyId", replyId)
				.param("actorId", actorId).update();
	}

	public boolean reactionExists(UUID userId, String targetType, UUID targetId) {
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from public.forum_reactions
				 where user_id = :userId and target_type = :targetType and target_id = :targetId)
				""").param("userId", userId).param("targetType", targetType).param("targetId", targetId)
				.query(Boolean.class).single());
	}

	public void addReaction(UUID userId, String targetType, UUID targetId) {
		jdbc.sql("""
				insert into public.forum_reactions (user_id, target_type, target_id)
				values (:userId, :targetType, :targetId)
				on conflict do nothing
				""").param("userId", userId).param("targetType", targetType).param("targetId", targetId)
				.update();
	}

	public void removeReaction(UUID userId, String targetType, UUID targetId) {
		jdbc.sql("""
				delete from public.forum_reactions
				 where user_id = :userId and target_type = :targetType and target_id = :targetId
				""").param("userId", userId).param("targetType", targetType).param("targetId", targetId)
				.update();
	}

	public int reactionCount(String targetType, UUID targetId) {
		String table = "reply".equals(targetType) ? "forum_replies" : "forum_topics";
		return jdbc.sql("select reaction_count from public." + table + " where id = :id")
				.param("id", targetId).query(Integer.class).optional().orElse(0);
	}

	public boolean bookmarkExists(UUID userId, UUID topicId) {
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from public.forum_bookmarks
				 where user_id = :userId and topic_id = :topicId)
				""").param("userId", userId).param("topicId", topicId).query(Boolean.class).single());
	}

	public void addBookmark(UUID userId, UUID topicId) {
		jdbc.sql("""
				insert into public.forum_bookmarks (user_id, topic_id)
				values (:userId, :topicId)
				on conflict do nothing
				""").param("userId", userId).param("topicId", topicId).update();
	}

	public void removeBookmark(UUID userId, UUID topicId) {
		jdbc.sql("delete from public.forum_bookmarks where user_id = :userId and topic_id = :topicId")
				.param("userId", userId).param("topicId", topicId).update();
	}

	public List<Map<String, Object>> bookmarks(UUID userId) {
		return DbRows.list(jdbc, TOPIC_DETAIL + """
				 join public.forum_bookmarks bm on bm.topic_id = t.id and bm.user_id = :viewer
				 where t.status = 'published' and t.deleted_at is null
				 order by bm.created_at desc
				 limit 50
				""", Map.of("viewer", userId));
	}

	public Optional<Map<String, Object>> insertReport(UUID reporterId, String targetType, UUID targetId,
			String reasonCode, String details) {
		return DbRows.one(jdbc, """
				insert into public.forum_reports (reporter_id, target_type, target_id, reason_code, details)
				values (:reporterId, :targetType, :targetId, :reasonCode, :details)
				returning id, reporter_id, target_type, target_id, reason_code, details, status, created_at
				""", Map.of(
				"reporterId", reporterId,
				"targetType", targetType,
				"targetId", targetId,
				"reasonCode", reasonCode,
				"details", details == null ? "" : details));
	}

	public List<Map<String, Object>> reports(String status, int limit) {
		StringBuilder sql = new StringBuilder("""
				select r.id, r.reporter_id, r.target_type, r.target_id, r.reason_code, r.details,
				       r.status, r.reviewed_by, r.reviewed_at, r.resolution_note, r.created_at,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as reporter_name,
				       case when r.target_type = 'topic'
				            then (select t.title from public.forum_topics t where t.id = r.target_id)
				            else (select left(p.body, 160) from public.forum_replies p where p.id = r.target_id)
				       end as target_excerpt
				  from public.forum_reports r
				  join auth.users u on u.id = r.reporter_id
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		if (status != null && !status.isBlank()) {
			sql.append(" where r.status = :status");
			params.put("status", status);
		}
		sql.append(" order by r.created_at desc limit :limit");
		params.put("limit", limit);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> report(UUID id) {
		return DbRows.one(jdbc, "select * from public.forum_reports where id = :id", Map.of("id", id));
	}

	public void resolveReport(UUID id, String status, String resolutionNote, UUID reviewerId) {
		jdbc.sql("""
				update public.forum_reports
				   set status = :status,
				       resolution_note = :note,
				       reviewed_by = :reviewerId,
				       reviewed_at = now()
				 where id = :id
				""")
				.param("id", id)
				.param("status", status)
				.param("note", resolutionNote)
				.param("reviewerId", reviewerId)
				.update();
	}

	public List<Map<String, Object>> adminTopics(String status, int limit) {
		StringBuilder sql = new StringBuilder("""
				select t.id, t.space_id, s.slug as space_slug, s.name as space_name,
				       t.author_id, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name,
				       t.title, left(t.body, 320) as excerpt, t.book_key, t.chapter_ref,
				       t.spoiler_level, t.status, t.is_pinned, t.is_locked,
				       t.reply_count, t.reaction_count, t.created_at, t.updated_at
				  from public.forum_topics t
				  join public.forum_spaces s on s.id = t.space_id
				  join auth.users u on u.id = t.author_id
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		if (status != null && !status.isBlank()) {
			sql.append(" where t.status = :status");
			params.put("status", status);
		}
		sql.append(" order by t.created_at asc limit :limit");
		params.put("limit", limit);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public List<Map<String, Object>> adminReplies(String status, int limit) {
		StringBuilder sql = new StringBuilder("""
				select r.id, r.topic_id, r.author_id, r.parent_id, left(r.body, 320) as excerpt,
				       r.status, r.reaction_count, r.created_at, r.updated_at,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name,
				       t.title as topic_title
				  from public.forum_replies r
				  join auth.users u on u.id = r.author_id
				  join public.forum_topics t on t.id = r.topic_id
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		if (status != null && !status.isBlank()) {
			sql.append(" where r.status = :status");
			params.put("status", status);
		}
		sql.append(" order by r.created_at asc limit :limit");
		params.put("limit", limit);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public boolean trusted(UUID userId) {
		return Boolean.TRUE.equals(jdbc.sql("""
				select (u.created_at < now() - interval '24 hours')
				   and ((select count(*) from public.forum_topics t
				          where t.author_id = u.id and t.status = 'published'
				            and t.created_at > now() - interval '30 days')
				      + (select count(*) from public.forum_replies r
				          where r.author_id = u.id and r.status = 'published'
				            and r.created_at > now() - interval '30 days')) >= 3
				  from auth.users u
				 where u.id = :id
				""").param("id", userId).query(Boolean.class).optional().orElse(false));
	}
}
