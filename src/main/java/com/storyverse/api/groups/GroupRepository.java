package com.storyverse.api.groups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class GroupRepository {

	/** Denúncias pendentes de contas diferentes que ocultam a mensagem até a moderação revisar. */
	static final int AUTO_HIDE_REPORTS = 3;

	private static final String GROUP_ROW = """
			select g.id, g.name, g.description, g.photo_url, g.visibility, g.owner_id, g.book_key, g.book_title, g.book_cover,
			       g.goal_chapter, g.goal_until, g.last_message_at, g.created_at,
			       (select count(*) from public.chat_members c
			         where c.group_id = g.id and c.banned_at is null) as member_count,
			       (m.user_id is not null and m.banned_at is null) as is_member,
			       case when m.banned_at is null then m.role end as my_role,
			       coalesce(m.muted, false) as muted,
			       coalesce(m.last_read_id, 0) as last_read_id,
			       case when m.user_id is not null and m.banned_at is null then
			         (select count(*) from (select 1 from public.chat_messages x
			                                 where x.group_id = g.id and x.id > m.last_read_id
			                                   and x.deleted_at is null and x.author_id <> :viewer
			                                 limit 100) unread)
			       else 0 end as unread,
			       case when m.role in ('owner', 'moderator') and m.banned_at is null then g.invite_code end as invite_code,
			       lm.id as last_message_id, lm.author_name as last_author_name,
			       case when lm.spoiler_chapter is not null then null else left(lm.body, 120) end as last_body
			  from public.chat_groups g
			  left join public.chat_members m on m.group_id = g.id and m.user_id = :viewer
			  left join lateral (
			    select x.id, x.body, x.spoiler_chapter, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name
			      from public.chat_messages x
			      join auth.users u on u.id = x.author_id
			     where x.group_id = g.id and x.deleted_at is null
			     order by x.id desc
			     limit 1
			  ) lm on m.user_id is not null and m.banned_at is null
			""";

	private static final String MESSAGE_ROW = """
			select x.id, x.group_id, x.author_id,
			       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name,
			       coalesce(u.raw_user_meta_data->>'avatar', '') as author_avatar,
			       case when x.deleted_at is not null then null
			            when x.author_id <> :viewer and hidden.n >= %d then null
			            else x.body end as body,
			       x.spoiler_chapter, x.reply_to, x.client_id, x.created_at,
			       x.deleted_at is not null as deleted,
			       (x.deleted_at is null and x.author_id <> :viewer and hidden.n >= %d) as hidden
			  from public.chat_messages x
			  join auth.users u on u.id = x.author_id
			  cross join lateral (
			    select count(distinct r.reporter_id) as n from public.chat_reports r
			     where r.message_id = x.id and r.status = 'pending'
			  ) hidden
			""".formatted(AUTO_HIDE_REPORTS, AUTO_HIDE_REPORTS);

	private final JdbcClient jdbc;

	public GroupRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> myGroups(UUID viewer) {
		return DbRows.list(jdbc, GROUP_ROW + """
				 where m.user_id = :viewer and m.banned_at is null and g.deleted_at is null
				 order by coalesce(g.last_message_at, m.joined_at) desc
				 limit 100
				""", Map.of("viewer", viewer));
	}

	public List<Map<String, Object>> publicGroups(UUID viewer, String query, String bookKey, int limit) {
		StringBuilder sql = new StringBuilder(GROUP_ROW)
				.append(" where g.visibility = 'public' and g.deleted_at is null");
		Map<String, Object> params = new HashMap<>();
		params.put("viewer", viewer);
		params.put("limit", limit);
		if (query != null) {
			sql.append(" and (g.name ilike :q or g.book_title ilike :q)");
			params.put("q", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (bookKey != null) {
			sql.append(" and g.book_key = :book");
			params.put("book", bookKey);
		}
		sql.append(" order by g.last_message_at desc nulls last, g.created_at desc limit :limit");
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> group(UUID viewer, UUID id) {
		return DbRows.one(jdbc, GROUP_ROW + " where g.id = :id and g.deleted_at is null",
				Map.of("viewer", viewer, "id", id));
	}

	public Optional<Map<String, Object>> groupByInvite(String code) {
		return DbRows.one(jdbc, """
				select id, visibility from public.chat_groups where invite_code = :code and deleted_at is null
				""", Map.of("code", code));
	}

	/** Estado da conta no grupo, inclusive removida (banned_at). Vazio: nunca entrou. */
	public Optional<Map<String, Object>> membership(UUID groupId, UUID userId) {
		return DbRows.one(jdbc, """
				select m.role, m.banned_at, m.last_read_id, g.visibility, g.owner_id
				  from public.chat_members m
				  join public.chat_groups g on g.id = m.group_id and g.deleted_at is null
				 where m.group_id = :group and m.user_id = :user
				""", Map.of("group", groupId, "user", userId));
	}

	public Optional<Map<String, Object>> groupState(UUID id) {
		return DbRows.one(jdbc, """
				select id, visibility, owner_id, deleted_at from public.chat_groups where id = :id
				""", Map.of("id", id));
	}

	public int countOwnedGroups(UUID owner) {
		return jdbc.sql("select count(*) from public.chat_groups where owner_id = :owner and deleted_at is null")
				.param("owner", owner).query(Integer.class).single();
	}

	public int countMemberships(UUID userId) {
		return jdbc.sql("""
				select count(*) from public.chat_members m
				  join public.chat_groups g on g.id = m.group_id and g.deleted_at is null
				 where m.user_id = :user and m.banned_at is null
				""").param("user", userId).query(Integer.class).single();
	}

	public int countMembers(UUID groupId) {
		return jdbc.sql("select count(*) from public.chat_members where group_id = :group and banned_at is null")
				.param("group", groupId).query(Integer.class).single();
	}

	public boolean accountOlderThanHours(UUID userId, int hours) {
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from auth.users where id = :id and created_at <= now() - make_interval(hours => :hours))
				""").param("id", userId).param("hours", hours).query(Boolean.class).single());
	}

	public UUID insertGroup(UUID owner, GroupFields fields, String inviteCode) {
		UUID id = jdbc.sql("""
				insert into public.chat_groups
				  (name, description, photo_url, visibility, owner_id, book_key, book_title, book_cover, goal_chapter, goal_until, invite_code)
				values (:name, :description, :photo_url, :visibility, :owner, :book_key, :book_title, :book_cover, :goal_chapter,
				        cast(:goal_until as date), :invite)
				returning id
				""")
				.param("name", fields.name())
				.param("description", fields.description())
				.param("photo_url", fields.photoUrl())
				.param("visibility", fields.visibility())
				.param("owner", owner)
				.param("book_key", fields.bookKey())
				.param("book_title", fields.bookTitle())
				.param("book_cover", fields.bookCover())
				.param("goal_chapter", fields.goalChapter())
				.param("goal_until", fields.goalUntil())
				.param("invite", inviteCode)
				.query(UUID.class).single();
		jdbc.sql("insert into public.chat_members (group_id, user_id, role) values (:group, :owner, 'owner')")
				.param("group", id).param("owner", owner).update();
		return id;
	}

	public void updateGroup(UUID id, GroupFields fields) {
		jdbc.sql("""
				update public.chat_groups
				   set name = :name, description = :description, photo_url = :photo_url, visibility = :visibility,
				       book_key = :book_key, book_title = :book_title, book_cover = :book_cover,
				       goal_chapter = :goal_chapter, goal_until = cast(:goal_until as date), updated_at = now()
				 where id = :id
				""")
				.param("id", id)
				.param("name", fields.name())
				.param("description", fields.description())
				.param("photo_url", fields.photoUrl())
				.param("visibility", fields.visibility())
				.param("book_key", fields.bookKey())
				.param("book_title", fields.bookTitle())
				.param("book_cover", fields.bookCover())
				.param("goal_chapter", fields.goalChapter())
				.param("goal_until", fields.goalUntil())
				.update();
	}

	public void softDeleteGroup(UUID id) {
		jdbc.sql("update public.chat_groups set deleted_at = now(), updated_at = now() where id = :id")
				.param("id", id).update();
	}

	public void rotateInvite(UUID id, String code) {
		jdbc.sql("update public.chat_groups set invite_code = :code, updated_at = now() where id = :id")
				.param("id", id).param("code", code).update();
	}

	public void addMember(UUID groupId, UUID userId) {
		jdbc.sql("""
				insert into public.chat_members (group_id, user_id, role)
				values (:group, :user, 'member')
				on conflict (group_id, user_id) do nothing
				""").param("group", groupId).param("user", userId).update();
		// Só depois de entrar a RLS mostra as mensagens: o histórico não conta como não lido.
		jdbc.sql("""
				update public.chat_members
				   set last_read_id = coalesce((select max(id) from public.chat_messages where group_id = :group), 0)
				 where group_id = :group and user_id = :user
				""").param("group", groupId).param("user", userId).update();
	}

	public void removeMember(UUID groupId, UUID userId) {
		jdbc.sql("delete from public.chat_members where group_id = :group and user_id = :user")
				.param("group", groupId).param("user", userId).update();
	}

	public void banMember(UUID groupId, UUID userId) {
		jdbc.sql("""
				update public.chat_members set banned_at = now(), role = 'member'
				 where group_id = :group and user_id = :user
				""").param("group", groupId).param("user", userId).update();
	}

	public void setRole(UUID groupId, UUID userId, String role) {
		jdbc.sql("update public.chat_members set role = :role where group_id = :group and user_id = :user and banned_at is null")
				.param("group", groupId).param("user", userId).param("role", role).update();
	}

	public void setMuted(UUID groupId, UUID userId, boolean muted) {
		jdbc.sql("update public.chat_members set muted = :muted where group_id = :group and user_id = :user")
				.param("group", groupId).param("user", userId).param("muted", muted).update();
	}

	public void markRead(UUID groupId, UUID userId, long lastId) {
		jdbc.sql("""
				update public.chat_members
				   set last_read_id = least(:last, coalesce((select max(id) from public.chat_messages where group_id = :group), 0))
				 where group_id = :group and user_id = :user and last_read_id < :last
				""").param("group", groupId).param("user", userId).param("last", lastId).update();
	}

	/** Membros ativos, com o progresso no livro do grupo (estante) quando houver. */
	public List<Map<String, Object>> members(UUID groupId) {
		return DbRows.list(jdbc, """
				select m.user_id, m.role, m.joined_at,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name,
				       coalesce(u.raw_user_meta_data->>'avatar', '') as avatar,
				       e.progress_percent, e.status as book_status
				  from public.chat_members m
				  join auth.users u on u.id = m.user_id
				  left join public.chat_member_progress(:group) e on e.user_id = m.user_id
				 where m.group_id = :group and m.banned_at is null
				 order by case m.role when 'owner' then 0 when 'moderator' then 1 else 2 end, m.joined_at
				 limit 200
				""", Map.of("group", groupId));
	}

	public List<UUID> activeMemberIds(UUID groupId) {
		return jdbc.sql("select user_id from public.chat_members where group_id = :group and banned_at is null")
				.param("group", groupId).query(UUID.class).list();
	}

	public List<Map<String, Object>> messages(UUID viewer, UUID groupId, Long before, Long after, int limit) {
		StringBuilder sql = new StringBuilder(MESSAGE_ROW).append(" where x.group_id = :group");
		Map<String, Object> params = new HashMap<>();
		params.put("viewer", viewer);
		params.put("group", groupId);
		params.put("limit", limit);
		if (after != null) {
			// Reconexão: o que chegou depois do último visto, do mais antigo para o mais novo.
			sql.append(" and x.id > :after order by x.id asc limit :limit");
			params.put("after", after);
			return withReactions(viewer, DbRows.list(jdbc, sql.toString(), params));
		}
		if (before != null) {
			sql.append(" and x.id < :before");
			params.put("before", before);
		}
		sql.append(" order by x.id desc limit :limit");
		List<Map<String, Object>> rows = new ArrayList<>(DbRows.list(jdbc, sql.toString(), params));
		java.util.Collections.reverse(rows);
		return withReactions(viewer, rows);
	}

	public Optional<Map<String, Object>> message(UUID viewer, long id) {
		return DbRows.one(jdbc, MESSAGE_ROW + " where x.id = :id", Map.of("viewer", viewer, "id", id))
				.map(row -> withReactions(viewer, List.of(row)).get(0));
	}

	public Optional<Map<String, Object>> messageState(long id) {
		return DbRows.one(jdbc, """
				select id, group_id, author_id, deleted_at from public.chat_messages where id = :id
				""", Map.of("id", id));
	}

	public Optional<Long> messageByClientId(UUID author, UUID clientId) {
		return jdbc.sql("select id from public.chat_messages where author_id = :author and client_id = :client")
				.param("author", author).param("client", clientId).query(Long.class).optional();
	}

	public long insertMessage(UUID groupId, UUID author, String body, Integer spoilerChapter, Long replyTo, UUID clientId) {
		long id = jdbc.sql("""
				insert into public.chat_messages (group_id, author_id, body, spoiler_chapter, reply_to, client_id)
				values (:group, :author, :body, :spoiler, :reply, :client)
				returning id
				""")
				.param("group", groupId).param("author", author).param("body", body)
				.param("spoiler", spoilerChapter).param("reply", replyTo).param("client", clientId)
				.query(Long.class).single();
		// Quem escreve já leu até a própria mensagem.
		jdbc.sql("update public.chat_members set last_read_id = :id where group_id = :group and user_id = :author")
				.param("id", id).param("group", groupId).param("author", author).update();
		return id;
	}

	public void softDeleteMessage(long id, UUID by) {
		jdbc.sql("update public.chat_messages set deleted_at = now(), deleted_by = :by where id = :id and deleted_at is null")
				.param("id", id).param("by", by).update();
	}

	public void addReaction(long messageId, UUID userId, String emoji) {
		jdbc.sql("""
				insert into public.chat_reactions (message_id, user_id, emoji) values (:msg, :user, :emoji)
				on conflict do nothing
				""").param("msg", messageId).param("user", userId).param("emoji", emoji).update();
	}

	public void removeReaction(long messageId, UUID userId, String emoji) {
		jdbc.sql("delete from public.chat_reactions where message_id = :msg and user_id = :user and emoji = :emoji")
				.param("msg", messageId).param("user", userId).param("emoji", emoji).update();
	}

	/** Contagem por emoji, sem dizer quem reagiu (só se o próprio leitor reagiu). */
	public List<Map<String, Object>> reactions(UUID viewer, long messageId) {
		return DbRows.list(jdbc, """
				select emoji, count(*) as count, bool_or(user_id = :viewer) as mine
				  from public.chat_reactions where message_id = :msg
				 group by emoji order by min(created_at)
				""", Map.of("viewer", viewer, "msg", messageId));
	}

	public Optional<Map<String, Object>> insertReport(UUID reporter, long messageId, String reason, String details) {
		return DbRows.one(jdbc, """
				insert into public.chat_reports (reporter_id, message_id, reason_code, details)
				values (:reporter, :msg, :reason, :details)
				returning id, message_id, reason_code, status, created_at
				""", params("reporter", reporter, "msg", messageId, "reason", reason, "details", details));
	}

	public List<Map<String, Object>> reports(String status, int limit) {
		StringBuilder sql = new StringBuilder("""
				select r.id, r.message_id, r.reason_code, r.details, r.status, r.created_at, r.reviewed_at,
				       r.resolution_note, r.reporter_id,
				       coalesce(ru.raw_user_meta_data->>'name', 'Leitor') as reporter_name,
				       x.group_id, g.name as group_name, x.author_id,
				       coalesce(au.raw_user_meta_data->>'name', 'Leitor') as author_name,
				       x.body, x.created_at as message_created_at, x.deleted_at as message_deleted_at,
				       (select count(*) from public.chat_reports o where o.message_id = r.message_id and o.status = 'pending')
				         as pending_reports
				  from public.chat_reports r
				  join public.chat_messages x on x.id = r.message_id
				  join public.chat_groups g on g.id = x.group_id
				  join auth.users ru on ru.id = r.reporter_id
				  join auth.users au on au.id = x.author_id
				""");
		Map<String, Object> params = new HashMap<>();
		params.put("limit", limit);
		if (status != null) {
			sql.append(" where r.status = :status");
			params.put("status", status);
		}
		sql.append(" order by r.created_at desc limit :limit");
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> report(UUID id) {
		return DbRows.one(jdbc, "select id, message_id, status from public.chat_reports where id = :id", Map.of("id", id));
	}

	/** Resolve todas as denúncias pendentes da mesma mensagem (a decisão vale para a mensagem). */
	public void resolveReports(long messageId, String status, String note, UUID reviewer) {
		jdbc.sql("""
				update public.chat_reports
				   set status = :status, resolution_note = :note, reviewed_by = :reviewer, reviewed_at = now()
				 where message_id = :msg and status = 'pending'
				""").param("msg", messageId).param("status", status).param("note", note).param("reviewer", reviewer).update();
	}

	private List<Map<String, Object>> withReactions(UUID viewer, List<Map<String, Object>> rows) {
		if (rows.isEmpty()) return rows;
		List<Long> ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
		Map<Long, List<Map<String, Object>>> byMessage = new HashMap<>();
		for (Map<String, Object> r : DbRows.list(jdbc, """
				select message_id, emoji, count(*) as count, bool_or(user_id = :viewer) as mine
				  from public.chat_reactions where message_id in (:ids)
				 group by message_id, emoji order by min(created_at)
				""", Map.of("viewer", viewer, "ids", ids))) {
			Map<String, Object> reaction = new LinkedHashMap<>();
			reaction.put("emoji", r.get("emoji"));
			reaction.put("count", r.get("count"));
			reaction.put("mine", r.get("mine"));
			byMessage.computeIfAbsent(((Number) r.get("message_id")).longValue(), k -> new ArrayList<>()).add(reaction);
		}
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Map<String, Object> copy = new LinkedHashMap<>(row);
			copy.put("reactions", byMessage.getOrDefault(((Number) row.get("id")).longValue(), List.of()));
			out.add(copy);
		}
		return out;
	}

	/** Map.of não aceita null; parâmetros opcionais passam por aqui. */
	private static Map<String, Object> params(Object... pairs) {
		Map<String, Object> out = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
		return out;
	}
}
