package com.storyverse.api.feature;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.admin.AdminCursor;
import com.storyverse.api.admin.EmailMask;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;

/**
 * Feature gate por conta, aplicado no servidor. A UI recebe as features pelas capabilities, mas
 * qualquer endpoint protegido deve chamar {@link #require(UUID, String)}.
 */
@Service
public class FeatureAccessService {

	/** Features que podem ser concedidas hoje. Ao criar outra, inclua aqui. */
	private static final Set<String> KNOWN = Set.of("library", "groups");

	static final int USERS_PAGE_SIZE = 50;

	private final JdbcClient jdbc;
	private final AuthzService authz;
	/** Ligadas para todas as contas ({@code app.features.everyone}): não dependem de concessão. */
	private final Set<String> everyone;

	public FeatureAccessService(JdbcClient jdbc, AuthzService authz,
			@Value("${app.features.everyone:}") String everyone) {
		this.jdbc = jdbc;
		this.authz = authz;
		this.everyone = Arrays.stream(everyone.split(","))
				.map(String::trim)
				.filter(KNOWN::contains)
				.collect(Collectors.toUnmodifiableSet());
	}

	public boolean forEveryone(String feature) {
		return everyone.contains(feature);
	}

	@Transactional(readOnly = true)
	public List<String> features(UUID userId) {
		return features(userId, userId);
	}

	/** Features de {@code userId} vistas por {@code viewerId}: o RLS avalia quem consulta, não o alvo. */
	@Transactional(readOnly = true)
	public List<String> features(UUID viewerId, UUID userId) {
		DbSession.setUser(jdbc, viewerId);
		Set<String> found = new TreeSet<>(everyone);
		found.addAll(jdbc.sql("""
				select feature from public.feature_access
				 where user_id = :uid and enabled
				""").param("uid", userId).query(String.class).list());
		return List.copyOf(found);
	}

	@Transactional(readOnly = true)
	public boolean enabled(UUID userId, String feature) {
		validateKnown(feature);
		if (everyone.contains(feature)) {
			return true;
		}
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("""
				select exists(select 1 from public.feature_access
				               where user_id = :uid and feature = :feature and enabled)
				""").param("uid", userId).param("feature", feature).query(Boolean.class).single());
	}

	@Transactional(readOnly = true)
	public void require(UUID userId, String feature) {
		if (!enabled(userId, feature)) {
			throw new AuthException(403, "Recurso indisponível para esta conta.", "FEATURE_DISABLED");
		}
	}

	@Transactional
	public void set(UUID actorId, UUID targetUserId, String feature, boolean enabled) {
		validateKnown(feature);
		if (everyone.contains(feature)) {
			throw new AuthException(409, "Este recurso está ligado para todas as contas.");
		}
		DbSession.setUser(jdbc, actorId);
		jdbc.sql("""
				insert into public.feature_access
					(user_id, feature, enabled, granted_by, granted_at, updated_at)
				values (:target, :feature, :enabled, :actor, now(), now())
				on conflict (user_id, feature) do update
				   set enabled = excluded.enabled,
				       granted_by = excluded.granted_by,
				       updated_at = now()
				""")
				.param("target", targetUserId)
				.param("feature", feature)
				.param("enabled", enabled)
				.param("actor", actorId)
				.update();
	}

	/** Concessão pelo painel: muda a feature e grava a auditoria na mesma transação. */
	@Transactional
	public void grant(UUID actorId, UUID targetUserId, String feature, boolean enabled) {
		set(actorId, targetUserId, feature, enabled);
		authz.audit(actorId, "feature.set", "user", targetUserId.toString(),
				Map.of("feature", feature, "enabled", enabled));
	}

	public static Set<String> known() {
		return KNOWN;
	}

	/** Catálogo do painel: todas as features conhecidas e quantas contas têm cada uma ligada. */
	@Transactional(readOnly = true)
	public List<FeatureCatalogEntry> catalog(UUID actorId) {
		authz.requireRole(actorId, AuthzService.SUPERADMIN);
		DbSession.setUser(jdbc, actorId);
		Map<String, Long> counts = new HashMap<>();
		jdbc.sql("select feature, count(*) as total from public.feature_access where enabled group by feature")
				.query((rs, rowNum) -> counts.put(rs.getString("feature"), rs.getLong("total"))).list();
		return KNOWN.stream().sorted()
				.map(feature -> new FeatureCatalogEntry(feature, counts.getOrDefault(feature, 0L),
						everyone.contains(feature)))
				.toList();
	}

	/**
	 * Contas com a feature ligada, da mais recente para a mais antiga. A ordem e o cursor usam
	 * {@code updated_at} truncado em milissegundos (o cursor guarda millis), mais {@code user_id}.
	 */
	@Transactional(readOnly = true)
	public FeatureUsersPage usersWith(UUID actorId, String feature, String rawCursor) {
		authz.requireRole(actorId, AuthzService.SUPERADMIN);
		validateKnown(feature);
		AdminCursor cursor = AdminCursor.decode(rawCursor);
		DbSession.setUser(jdbc, actorId);
		OffsetDateTime cursorTime = cursor == null ? null : OffsetDateTime.ofInstant(cursor.time(), ZoneOffset.UTC);
		List<FeatureUserRow> found = jdbc.sql("""
				select fa.user_id,
				       coalesce(u.raw_user_meta_data->>'name', 'Leitor') as name,
				       u.email,
				       case when fa.granted_by is null then null
				            else coalesce(g.raw_user_meta_data->>'name', 'Leitor') end as granted_by_name,
				       date_trunc('milliseconds', fa.updated_at) as updated_at
				  from public.feature_access fa
				  join auth.users u on u.id = fa.user_id
				  left join auth.users g on g.id = fa.granted_by
				 where fa.feature = :feature and fa.enabled
				   and (cast(:cursorTime as timestamptz) is null
				        or (date_trunc('milliseconds', fa.updated_at), fa.user_id)
				           < (cast(:cursorTime as timestamptz), cast(:cursorId as uuid)))
				 order by date_trunc('milliseconds', fa.updated_at) desc, fa.user_id desc
				 limit :limit
				""")
				.param("feature", feature)
				.param("cursorTime", cursorTime, Types.TIMESTAMP_WITH_TIMEZONE)
				.param("cursorId", cursor == null ? null : cursor.id(), Types.OTHER)
				.param("limit", USERS_PAGE_SIZE + 1)
				.query(FeatureAccessService::userRow).list();
		boolean more = found.size() > USERS_PAGE_SIZE;
		List<FeatureUserRow> items = more ? List.copyOf(found.subList(0, USERS_PAGE_SIZE)) : found;
		String next = null;
		if (more) {
			FeatureUserRow last = items.get(items.size() - 1);
			next = AdminCursor.from(last.updatedAt(), last.userId()).encode();
		}
		return new FeatureUsersPage(items, next);
	}

	private static FeatureUserRow userRow(ResultSet rs, int rowNum) throws SQLException {
		Instant updatedAt = rs.getObject("updated_at", OffsetDateTime.class).toInstant();
		return new FeatureUserRow(rs.getObject("user_id", UUID.class), rs.getString("name"),
				EmailMask.mask(rs.getString("email")), rs.getString("granted_by_name"), updatedAt);
	}

	private static void validateKnown(String feature) {
		if (feature == null || !KNOWN.contains(feature)) {
			throw new AuthException(400, "Feature inválida.");
		}
	}
}
