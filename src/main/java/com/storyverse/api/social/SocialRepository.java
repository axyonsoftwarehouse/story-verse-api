package com.storyverse.api.social;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

/**
 * Consultas da rede social. Todas rodam com {@code app.user_id} da conta (a RLS de
 * {@code user_follows} vale como segunda barreira no papel de runtime).
 */
@Repository
public class SocialRepository {

	/** Pessoa vista por {@code :viewer}: nome, rosto e como as duas se relacionam. */
	private static final String PERSON = """
			select u.id,
			       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name,
			       coalesce(u.raw_user_meta_data->>'avatar', '') as avatar,
			       case when starts_with(u.raw_user_meta_data->>'photo', :media || u.id::text || '/')
			            then u.raw_user_meta_data->>'photo' end as photo,
			       exists (select 1 from public.user_follows f where f.follower_id = :viewer and f.followee_id = u.id) as is_following,
			       exists (select 1 from public.user_follows f where f.follower_id = u.id and f.followee_id = :viewer) as follows_me
			""";

	private final JdbcClient jdbc;
	private final String media;

	public SocialRepository(JdbcClient jdbc,
			@Value("${app.public-api-url:https://api.storyverse.2.29.42.104.sslip.io}") String publicApiUrl) {
		this.jdbc = jdbc;
		this.media = publicApiUrl.trim().replaceAll("/+$", "") + "/api/v1/storage/public/profile-media/";
	}

	public boolean exists(UUID userId) {
		return jdbc.sql("select exists (select 1 from auth.users where id = :id)")
				.param("id", userId).query(Boolean.class).single();
	}

	/** Perfil com contadores; grupos em comum só conta os que a conta enxerga (os dela). */
	public Optional<Map<String, Object>> profile(UUID viewer, UUID userId) {
		return DbRows.one(jdbc, PERSON + """
				       , u.created_at as member_since,
				       nullif(btrim(u.raw_user_meta_data->>'bio'), '') as bio,
				       public.social_is_private(u.id) as is_private,
				       public.social_content_visible(u.id) as content_visible,
				       exists (select 1 from public.follow_requests r where r.requester_id = :viewer and r.target_id = u.id) as requested,
				       case when u.id = :viewer
				            then (select count(*) from public.follow_requests r where r.target_id = u.id) else 0 end as pending_requests,
				       (select count(*) from public.user_follows f where f.followee_id = u.id) as followers,
				       (select count(*) from public.user_follows f where f.follower_id = u.id) as following,
				       (select count(*) from public.user_follows a
				          join public.user_follows b on b.follower_id = a.followee_id and b.followee_id = a.follower_id
				         where a.follower_id = u.id) as connections,
				       (select count(*) from public.chat_members mine
				          join public.chat_members theirs on theirs.group_id = mine.group_id
				          join public.chat_groups g on g.id = mine.group_id and g.deleted_at is null
				         where mine.user_id = :viewer and mine.banned_at is null
				           and theirs.user_id = u.id and theirs.banned_at is null
				           and u.id <> :viewer) as groups_in_common
				  from auth.users u
				 where u.id = :user
				""", Map.of("viewer", viewer, "user", userId, "media", media));
	}

	/** Quem segue {@code :user}, do mais recente para o mais antigo. */
	public List<Map<String, Object>> followers(UUID viewer, UUID userId, int limit, int offset) {
		return DbRows.list(jdbc, PERSON + """
				       , f0.created_at as since
				  from public.user_follows f0
				  join auth.users u on u.id = f0.follower_id
				 where f0.followee_id = :user
				 order by f0.created_at desc, u.id
				 limit :limit offset :offset
				""", Map.of("viewer", viewer, "user", userId, "media", media, "limit", limit, "offset", offset));
	}

	/** Quem {@code :user} segue. */
	public List<Map<String, Object>> following(UUID viewer, UUID userId, int limit, int offset) {
		return DbRows.list(jdbc, PERSON + """
				       , f0.created_at as since
				  from public.user_follows f0
				  join auth.users u on u.id = f0.followee_id
				 where f0.follower_id = :user
				 order by f0.created_at desc, u.id
				 limit :limit offset :offset
				""", Map.of("viewer", viewer, "user", userId, "media", media, "limit", limit, "offset", offset));
	}

	/** Busca por parte do nome (sem e-mail: ninguém é achado pelo endereço). */
	public List<Map<String, Object>> search(UUID viewer, String query, int limit) {
		String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return DbRows.list(jdbc, PERSON + """
				  from auth.users u
				 where u.id <> :viewer
				   and coalesce(u.raw_user_meta_data->>'name', '') ilike :pattern
				 order by (select count(*) from public.user_follows f where f.followee_id = u.id) desc,
				          u.raw_user_meta_data->>'name', u.id
				 limit :limit
				""", Map.of("viewer", viewer, "pattern", pattern, "media", media, "limit", limit));
	}

	/**
	 * Sugestões: quem divide grupo com a conta e ela ainda não segue (mais grupos em comum
	 * primeiro); quem já segue a conta vem antes, para "seguir de volta".
	 */
	public List<Map<String, Object>> suggestions(UUID viewer, int limit) {
		return DbRows.list(jdbc, """
				with candidates as (
				  select theirs.user_id, count(distinct theirs.group_id) as shared
				    from public.chat_members mine
				    join public.chat_members theirs on theirs.group_id = mine.group_id
				    join public.chat_groups g on g.id = mine.group_id and g.deleted_at is null
				   where mine.user_id = :viewer and mine.banned_at is null
				     and theirs.banned_at is null and theirs.user_id <> :viewer
				   group by theirs.user_id
				  union all
				  select f.follower_id, 0 from public.user_follows f where f.followee_id = :viewer
				), c as (
				  select user_id, max(shared) as shared from candidates group by user_id
				)
				""" + PERSON + """
				       , c.shared as groups_in_common
				  from c
				  join auth.users u on u.id = c.user_id
				 where not exists (select 1 from public.user_follows f where f.follower_id = :viewer and f.followee_id = u.id)
				 order by follows_me desc, groups_in_common desc, u.id
				 limit :limit
				""", Map.of("viewer", viewer, "media", media, "limit", limit));
	}

	/** true quando passou a seguir agora (false se já seguia). */
	public boolean follow(UUID follower, UUID followee) {
		return jdbc.sql("""
				insert into public.user_follows (follower_id, followee_id) values (:follower, :followee)
				on conflict do nothing
				""").param("follower", follower).param("followee", followee).update() > 0;
	}

	public boolean unfollow(UUID follower, UUID followee) {
		return jdbc.sql("delete from public.user_follows where follower_id = :follower and followee_id = :followee")
				.param("follower", follower).param("followee", followee).update() > 0;
	}

	public int followingCount(UUID userId) {
		return jdbc.sql("select count(*) from public.user_follows where follower_id = :user")
				.param("user", userId).query(Integer.class).single();
	}

	/** Aviso no sininho (a função do banco confere o vínculo e evita repetir em 24 h). */
	public boolean notifyFollow(UUID followee) {
		return jdbc.sql("select public.social_notify_follow(:user)").param("user", followee)
				.query((rs, rowNum) -> rs.getObject(1)).optional().filter(java.util.Objects::nonNull).isPresent();
	}
	/* ------------------------------ Perfil público ------------------------------ */

	/** Vitrine publicada pelo app da pessoa (null se nunca publicou). */
	public Optional<Map<String, Object>> showcase(UUID userId) {
		return DbRows.one(jdbc, """
				select show_reading, badges::text as badges, badges_total, stats::text as stats, updated_at
				  from public.profile_showcase where user_id = :user
				""", Map.of("user", userId));
	}

	public boolean readingVisible(UUID userId) {
		return jdbc.sql("select public.social_reading_visible(:user)").param("user", userId).query(Boolean.class).single();
	}

	public List<Map<String, Object>> books(UUID userId, int limit, int offset) {
		return DbRows.list(jdbc, "select * from public.social_profile_books(:user, :limit, :offset)",
				Map.of("user", userId, "limit", limit, "offset", offset));
	}

	public Map<String, Object> readingCounts(UUID userId) {
		return DbRows.one(jdbc, "select * from public.social_profile_reading_counts(:user)", Map.of("user", userId))
				.orElse(Map.of("reading", 0, "finished", 0));
	}

	public List<Map<String, Object>> gifts(UUID userId) {
		return DbRows.list(jdbc, "select * from public.social_profile_gifts(:user)", Map.of("user", userId));
	}

	public void saveShowcase(UUID userId, String badgesJson, int badgesTotal, String statsJson) {
		jdbc.sql("""
				insert into public.profile_showcase (user_id, badges, badges_total, stats, updated_at)
				values (:user, cast(:badges as jsonb), :total, cast(:stats as jsonb), now())
				on conflict (user_id) do update
				   set badges = excluded.badges, badges_total = excluded.badges_total,
				       stats = excluded.stats, updated_at = now()
				""").param("user", userId).param("badges", badgesJson).param("total", badgesTotal).param("stats", statsJson).update();
	}

	/* ------------------------------ Perfil privado ------------------------------ */

	public boolean isPrivate(UUID userId) {
		return jdbc.sql("select public.social_is_private(:user)").param("user", userId).query(Boolean.class).single();
	}

	/** A conta da sessão vê leituras e listas de {@code userId}? */
	public boolean contentVisible(UUID userId) {
		return jdbc.sql("select public.social_content_visible(:user)").param("user", userId).query(Boolean.class).single();
	}

	public boolean isFollowing(UUID follower, UUID followee) {
		return jdbc.sql("select exists (select 1 from public.user_follows where follower_id = :follower and followee_id = :followee)")
				.param("follower", follower).param("followee", followee).query(Boolean.class).single();
	}

	/** true quando o pedido foi criado agora (false se já existia). */
	public boolean request(UUID requester, UUID target) {
		return jdbc.sql("""
				insert into public.follow_requests (requester_id, target_id) values (:requester, :target)
				on conflict do nothing
				""").param("requester", requester).param("target", target).update() > 0;
	}

	/** Cancelar (quem pediu) ou recusar (quem recebeu). */
	public boolean deleteRequest(UUID requester, UUID target) {
		return jdbc.sql("delete from public.follow_requests where requester_id = :requester and target_id = :target")
				.param("requester", requester).param("target", target).update() > 0;
	}

	/** Aceitar o pedido de {@code requester} para a conta da sessão. */
	public boolean acceptRequest(UUID requester) {
		return Boolean.TRUE.equals(jdbc.sql("select public.social_accept_follow_request(:requester)")
				.param("requester", requester).query(Boolean.class).single());
	}

	public int acceptAllRequests() {
		return jdbc.sql("select public.social_accept_all_follow_requests()").query(Integer.class).single();
	}

	/** Pedidos recebidos pela conta, do mais recente para o mais antigo. */
	public List<Map<String, Object>> requests(UUID viewer, int limit, int offset) {
		return DbRows.list(jdbc, PERSON + """
				       , r.created_at as since
				  from public.follow_requests r
				  join auth.users u on u.id = r.requester_id
				 where r.target_id = :viewer
				 order by r.created_at desc, u.id
				 limit :limit offset :offset
				""", Map.of("viewer", viewer, "media", media, "limit", limit, "offset", offset));
	}

	public boolean notifyFollowRequest(UUID target) {
		return jdbc.sql("select public.social_notify_follow_request(:user)").param("user", target)
				.query((rs, rowNum) -> rs.getObject(1)).optional().filter(java.util.Objects::nonNull).isPresent();
	}

	public boolean notifyFollowAccepted(UUID requester) {
		return jdbc.sql("select public.social_notify_follow_accepted(:user)").param("user", requester)
				.query((rs, rowNum) -> rs.getObject(1)).optional().filter(java.util.Objects::nonNull).isPresent();
	}

	public void savePrivate(UUID userId, boolean isPrivate) {
		jdbc.sql("""
				insert into public.profile_showcase (user_id, is_private) values (:user, :isPrivate)
				on conflict (user_id) do update set is_private = excluded.is_private, updated_at = now()
				""").param("user", userId).param("isPrivate", isPrivate).update();
	}

	public void saveShowReading(UUID userId, boolean show) {
		jdbc.sql("""
				insert into public.profile_showcase (user_id, show_reading) values (:user, :show)
				on conflict (user_id) do update set show_reading = excluded.show_reading, updated_at = now()
				""").param("user", userId).param("show", show).update();
	}
}
