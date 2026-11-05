package com.storyverse.api.groups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class GroupRepository {

	/** Denúncias pendentes de contas diferentes que ocultam a mensagem até a moderação revisar. */
	static final int AUTO_HIDE_REPORTS = 3;

	private static final String GROUP_ROW = """
			select g.id, g.name, g.description, g.photo_url, g.background, g.visibility, g.owner_id, g.book_key, g.book_title, g.book_cover,
			       g.goal_chapter, g.goal_until, g.last_message_at, g.created_at, g.mode, g.rp_premise,
			       case when m.banned_at is null then m.rp_character end as my_character,
			       case when m.banned_at is null then m.rp_photo end as my_character_photo,
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
			       lm.id as last_message_id, lm.author_name as last_author_name, lm.media_kind as last_media_kind, lm.kind as last_kind,
			       case when lm.spoiler_chapter is not null then null else left(lm.body, 120) end as last_body
			  from public.chat_groups g
			  left join public.chat_members m on m.group_id = g.id and m.user_id = :viewer
			  left join lateral (
			    select x.id, x.body, x.spoiler_chapter, x.media_kind, x.kind, coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name
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
			       case when starts_with(u.raw_user_meta_data->>'photo', :media || x.author_id::text || '/')
			            then u.raw_user_meta_data->>'photo' end as author_photo,
			       case when x.deleted_at is not null then null
			            when x.author_id <> :viewer and hidden.n >= %d then null
			            else x.body end as body,
			       x.spoiler_chapter, x.reply_to, x.client_id, x.created_at, x.kind,
			       x.media_kind, x.media_mime, x.media_duration_ms, x.media_width, x.media_height, x.media_expires_at,
			       (x.media_path is not null and x.deleted_at is null
			        and not (x.author_id <> :viewer and hidden.n >= %d)) as media_available,
			       x.deleted_at is not null as deleted,
			       (x.deleted_at is null and x.author_id <> :viewer and hidden.n >= %d) as hidden
			  from public.chat_messages x
			  join auth.users u on u.id = x.author_id
			  cross join lateral (
			    select count(distinct r.reporter_id) as n from public.chat_reports r
			     where r.message_id = x.id and r.status = 'pending'
			  ) hidden
			""".formatted(AUTO_HIDE_REPORTS, AUTO_HIDE_REPORTS, AUTO_HIDE_REPORTS);

	private final JdbcClient jdbc;
	/**
	 * Começo do endereço das fotos de perfil no Storage da API. A foto do perfil aceita qualquer
	 * URL; nos grupos só sai a que é um arquivo da própria pasta da pessoa (uma URL de fora
	 * serviria para rastrear quem abre a conversa).
	 */
	private final String media;

	public GroupRepository(JdbcClient jdbc,
			@Value("${app.public-api-url:https://api.storyverse.2.29.42.104.sslip.io}") String publicApiUrl) {
		this.jdbc = jdbc;
		this.media = publicApiUrl.trim().replaceAll("/+$", "") + "/api/v1/storage/public/profile-media/";
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
				select id, visibility from public.chat_group_by_invite(:code)
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
		// Função security definer: quem ainda está de fora só veria a própria linha pela RLS.
		return jdbc.sql("select public.chat_member_count(:group)")
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
				  (name, description, photo_url, background, visibility, owner_id, book_key, book_title, book_cover, goal_chapter, goal_until, invite_code)
				values (:name, :description, :photo_url, :background, :visibility, :owner, :book_key, :book_title, :book_cover, :goal_chapter,
				        cast(:goal_until as date), :invite)
				returning id
				""")
				.param("name", fields.name())
				.param("description", fields.description())
				.param("photo_url", fields.photoUrl())
				.param("background", fields.background())
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
				   set name = :name, description = :description, photo_url = :photo_url, background = :background, visibility = :visibility,
				       book_key = :book_key, book_title = :book_title, book_cover = :book_cover,
				       goal_chapter = :goal_chapter, goal_until = cast(:goal_until as date), updated_at = now()
				 where id = :id
				""")
				.param("id", id)
				.param("name", fields.name())
				.param("description", fields.description())
				.param("photo_url", fields.photoUrl())
				.param("background", fields.background())
				.param("visibility", fields.visibility())
				.param("book_key", fields.bookKey())
				.param("book_title", fields.bookTitle())
				.param("book_cover", fields.bookCover())
				.param("goal_chapter", fields.goalChapter())
				.param("goal_until", fields.goalUntil())
				.update();
	}

	/** Marca o grupo como apagado (função do banco: só dono ou equipe; ver V41). */
	public void softDeleteGroup(UUID id) {
		jdbc.sql("select public.chat_delete_group(:id)").param("id", id).query(Boolean.class).single();
	}

	public void rotateInvite(UUID id, String code) {
		jdbc.sql("update public.chat_groups set invite_code = :code, updated_at = now() where id = :id")
				.param("id", id).param("code", code).update();
	}

	/** Quem administra adiciona outra pessoa (função do banco confere o papel; ver V45). */
	public boolean addMemberByManager(UUID groupId, UUID userId) {
		return Boolean.TRUE.equals(jdbc.sql("select public.chat_add_member(:group, :user)")
				.param("group", groupId).param("user", userId).query(Boolean.class).single());
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

	/** Posições de "recebido" e "lido" da conta no grupo: [entregue, lido]. */
	public long[] receipts(UUID groupId, UUID userId) {
		return jdbc.sql("select last_delivered_id, last_read_id from public.chat_members where group_id = :group and user_id = :user")
				.param("group", groupId).param("user", userId)
				.query((rs, rowNum) -> new long[] { rs.getLong(1), rs.getLong(2) })
				.optional().orElse(new long[] { 0, 0 });
	}

	/** O aparelho recebeu até a mensagem `lastId` (nunca volta para trás nem passa da última). */
	public void markDelivered(UUID groupId, UUID userId, long lastId) {
		jdbc.sql("""
				update public.chat_members
				   set last_delivered_id = least(:last, coalesce((select max(id) from public.chat_messages where group_id = :group), 0))
				 where group_id = :group and user_id = :user and last_delivered_id < :last
				""").param("group", groupId).param("user", userId).param("last", lastId).update();
	}

	/** Quem escreveu mensagens no intervalo (de, até]: só essas pessoas se importam com o selo. */
	public List<UUID> authorsBetween(UUID groupId, long from, long to, UUID except) {
		if (to <= from) return List.of();
		return jdbc.sql("""
				select distinct author_id from public.chat_messages
				 where group_id = :group and id > :from and id <= :to and author_id <> :except and deleted_at is null
				""").param("group", groupId).param("from", from).param("to", to).param("except", except)
				.query(UUID.class).list();
	}

	public UUID ownerOf(UUID groupId) {
		return jdbc.sql("select owner_id from public.chat_groups where id = :group").param("group", groupId)
				.query(UUID.class).optional().orElse(null);
	}

	/* ------------------------------ Sininho ------------------------------ */
	// Avisos de "começou a seguir você" (rede social) não têm grupo: entram com group_id nulo.

	/** Aviso para outra pessoa do grupo (a função do banco confere que as duas estão nele). */
	public boolean notify(UUID user, String kind, UUID groupId, Long messageId, String emoji) {
		return jdbc.sql("select public.chat_notify(:user, :kind, :group, :message, :emoji)")
				.param("user", user).param("kind", kind).param("group", groupId).param("message", messageId).param("emoji", emoji)
				.query((rs, rowNum) -> rs.getObject(1)).optional().filter(java.util.Objects::nonNull).isPresent();
	}

	public List<Map<String, Object>> notifications(UUID viewer, int limit) {
		return DbRows.list(jdbc, """
				select n.id, n.kind, n.group_id, g.name as group_name, n.message_id, n.emoji, n.created_at,
				       n.read_at is not null as read, n.actor_id,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as actor_name,
				       coalesce(u.raw_user_meta_data->>'avatar', '') as actor_avatar,
				       case when starts_with(u.raw_user_meta_data->>'photo', :media || n.actor_id::text || '/')
				            then u.raw_user_meta_data->>'photo' end as actor_photo,
				       case when x.deleted_at is null and x.spoiler_chapter is null then left(x.body, 100) end as message_body,
				       x.media_kind as message_media_kind
				  from public.user_notifications n
				  left join public.chat_groups g on g.id = n.group_id
				  join auth.users u on u.id = n.actor_id
				  left join public.chat_messages x on x.id = n.message_id
				 where n.user_id = :viewer
				   and (n.group_id is null or (g.id is not null and g.deleted_at is null))
				 order by n.id desc
				 limit :limit
				""", Map.of("viewer", viewer, "limit", limit, "media", media));
	}

	public int unreadNotifications(UUID viewer) {
		return jdbc.sql("""
				select count(*) from public.user_notifications n
				  left join public.chat_groups g on g.id = n.group_id
				 where n.user_id = :viewer and n.read_at is null
				   and (n.group_id is null or (g.id is not null and g.deleted_at is null))
				""").param("viewer", viewer).query(Integer.class).single();
	}

	public void markNotificationsRead(UUID viewer) {
		jdbc.sql("update public.user_notifications set read_at = now() where user_id = :viewer and read_at is null")
				.param("viewer", viewer).update();
	}

	public int pruneNotifications() {
		return jdbc.sql("select public.chat_prune_notifications()").query(Integer.class).single();
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
				select m.user_id, m.role, m.joined_at, m.last_read_id, m.last_delivered_id, m.rp_character, m.rp_photo,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name,
				       coalesce(u.raw_user_meta_data->>'avatar', '') as avatar,
				       case when starts_with(u.raw_user_meta_data->>'photo', :media || m.user_id::text || '/')
				            then u.raw_user_meta_data->>'photo' end as photo,
				       e.progress_percent, e.status as book_status
				  from public.chat_members m
				  join auth.users u on u.id = m.user_id
				  left join public.chat_member_progress(:group) e on e.user_id = m.user_id
				 where m.group_id = :group and m.banned_at is null
				 order by case m.role when 'owner' then 0 when 'moderator' then 1 else 2 end, m.joined_at
				 limit 200
				""", Map.of("group", groupId, "media", media));
	}

	/** Quem divide algum grupo com a conta (para o aviso de online). Precisa de app.user_id. */
	public List<UUID> coMemberIds(UUID userId) {
		return jdbc.sql("""
				select distinct o.user_id
				  from public.chat_members m
				  join public.chat_groups g on g.id = m.group_id and g.deleted_at is null
				  join public.chat_members o on o.group_id = m.group_id and o.banned_at is null
				 where m.user_id = :user and m.banned_at is null and o.user_id <> :user
				""").param("user", userId).query(UUID.class).list();
	}

	/* ------------------------------ Roleplay ------------------------------ */

	public void setRoleplay(UUID groupId, String premise) {
		jdbc.sql("update public.chat_groups set mode = 'roleplay', rp_premise = :premise where id = :group")
				.param("group", groupId).param("premise", premise).update();
	}

	public void updatePremise(UUID groupId, String premise) {
		jdbc.sql("update public.chat_groups set rp_premise = :premise, updated_at = now() where id = :group and mode = 'roleplay'")
				.param("group", groupId).param("premise", premise).update();
	}

	public void setCharacter(UUID groupId, UUID userId, String character, String photo) {
		jdbc.sql("update public.chat_members set rp_character = :character, rp_photo = :photo where group_id = :group and user_id = :user")
				.param("group", groupId).param("user", userId).param("character", character).param("photo", photo).update();
	}

	/** Modo, premissa, dono, nome e livro do grupo (vazio se apagado ou invisível para a sessão). */
	public Optional<Map<String, Object>> roleplayInfo(UUID groupId) {
		return DbRows.one(jdbc, """
				select id, mode, rp_premise, owner_id, name, book_title from public.chat_groups
				 where id = :group and deleted_at is null
				""", Map.of("group", groupId));
	}

	/**
	 * Vez do narrador: no máximo uma narração a cada {@code cooldownSeconds} e {@code dailyCap} por
	 * dia (horário de Brasília). Atômico: duas chamadas juntas não narram duas vezes.
	 */
	public boolean claimNarration(UUID groupId, int cooldownSeconds, int dailyCap) {
		return jdbc.sql("""
				update public.chat_groups
				   set rp_last_narration_at = now(),
				       rp_narrations_count = case when rp_narrations_day = (now() at time zone 'America/Sao_Paulo')::date
				                                  then rp_narrations_count + 1 else 1 end,
				       rp_narrations_day = (now() at time zone 'America/Sao_Paulo')::date
				 where id = :group and mode = 'roleplay' and deleted_at is null
				   and (rp_last_narration_at is null or rp_last_narration_at < now() - make_interval(secs => :cooldown))
				   and (rp_narrations_day is distinct from (now() at time zone 'America/Sao_Paulo')::date or rp_narrations_count < :cap)
				returning id
				""").param("group", groupId).param("cooldown", cooldownSeconds).param("cap", dailyCap)
				.query(UUID.class).optional().isPresent();
	}

	/** Mensagens das pessoas desde a última narração (gatilho do narrador automático). */
	public int playerMessagesSinceNarration(UUID groupId) {
		return jdbc.sql("""
				select count(*) from public.chat_messages
				 where group_id = :group and kind = 'user' and deleted_at is null
				   and id > coalesce((select max(id) from public.chat_messages where group_id = :group and kind = 'narrator'), 0)
				""").param("group", groupId).query(Integer.class).single();
	}

	/** As últimas mensagens da história, da mais antiga para a mais nova, para o narrador ler. */
	public List<Map<String, Object>> storySoFar(UUID groupId, int limit) {
		List<Map<String, Object>> rows = new ArrayList<>(DbRows.list(jdbc, """
				select x.id, x.kind, x.body, x.media_kind,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as author_name, m.rp_character
				  from public.chat_messages x
				  join auth.users u on u.id = x.author_id
				  left join public.chat_members m on m.group_id = x.group_id and m.user_id = x.author_id
				 where x.group_id = :group and x.deleted_at is null
				 order by x.id desc
				 limit :limit
				""", Map.of("group", groupId, "limit", limit)));
		java.util.Collections.reverse(rows);
		return rows;
	}

	/** Quem joga e com qual personagem. */
	public List<Map<String, Object>> players(UUID groupId) {
		return DbRows.list(jdbc, """
				select coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name, m.rp_character
				  from public.chat_members m
				  join auth.users u on u.id = m.user_id
				 where m.group_id = :group and m.banned_at is null
				 order by m.joined_at
				 limit 50
				""", Map.of("group", groupId));
	}

	/** Fala do narrador (no nome do dono do grupo, cuja cota de IA pagou). */
	public long insertNarration(UUID groupId, UUID owner, String text) {
		return jdbc.sql("""
				insert into public.chat_messages (group_id, author_id, body, client_id, kind)
				values (:group, :owner, :body, gen_random_uuid(), 'narrator')
				returning id
				""").param("group", groupId).param("owner", owner).param("body", text).query(Long.class).single();
	}

	public String groupName(UUID groupId) {
		return jdbc.sql("select name from public.chat_groups where id = :group").param("group", groupId)
				.query(String.class).optional().orElse("Grupo");
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
		params.put("media", media);
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
		return DbRows.one(jdbc, MESSAGE_ROW + " where x.id = :id", Map.of("viewer", viewer, "id", id, "media", media))
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
		return insertMessage(groupId, author, body, spoilerChapter, replyTo, clientId, null);
	}

	/** Dados da foto/áudio de uma mensagem (o arquivo fica no {@link ChatMediaStore}). */
	record Media(String kind, String path, String mime, long bytes, Integer durationMs, Integer width, Integer height,
			java.time.OffsetDateTime expiresAt) {
	}

	public long insertMessage(UUID groupId, UUID author, String body, Integer spoilerChapter, Long replyTo, UUID clientId, Media media) {
		long id = jdbc.sql("""
				insert into public.chat_messages (group_id, author_id, body, spoiler_chapter, reply_to, client_id,
				  media_kind, media_path, media_mime, media_bytes, media_duration_ms, media_width, media_height, media_expires_at)
				values (:group, :author, :body, :spoiler, :reply, :client,
				  :kind, :path, :mime, :bytes, :duration, :width, :height, :expires)
				returning id
				""")
				.param("group", groupId).param("author", author).param("body", body)
				.param("spoiler", spoilerChapter).param("reply", replyTo).param("client", clientId)
				.param("kind", media == null ? null : media.kind())
				.param("path", media == null ? null : media.path())
				.param("mime", media == null ? null : media.mime())
				.param("bytes", media == null ? null : (int) media.bytes())
				.param("duration", media == null ? null : media.durationMs())
				.param("width", media == null ? null : media.width())
				.param("height", media == null ? null : media.height())
				.param("expires", media == null ? null : media.expiresAt())
				.query(Long.class).single();
		// Quem escreve já leu até a própria mensagem.
		jdbc.sql("update public.chat_members set last_read_id = :id where group_id = :group and user_id = :author")
				.param("id", id).param("group", groupId).param("author", author).update();
		return id;
	}

	public void softDeleteMessage(long id, UUID by) {
		jdbc.sql("""
				update public.chat_messages set deleted_at = now(), deleted_by = :by, media_path = null
				 where id = :id and deleted_at is null
				""").param("id", id).param("by", by).update();
	}

	/** Arquivo da foto/áudio de uma mensagem (vazio: sem mídia, apagada ou vencida). */
	public Optional<Map<String, Object>> mediaFile(long id) {
		return DbRows.one(jdbc, """
				select group_id, author_id, media_path, media_mime, deleted_at from public.chat_messages
				 where id = :id and media_path is not null and deleted_at is null
				""", Map.of("id", id));
	}

	/** Solta as mídias vencidas e devolve os caminhos para apagar do disco. */
	public List<String> expireMedia() {
		return jdbc.sql("select media_path from public.chat_expire_media()").query(String.class).list();
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
