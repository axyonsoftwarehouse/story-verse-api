package com.storyverse.api.admin;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.feature.FeatureAccessService;

/** Visão geral: só os blocos que o papel do ator pode ver (spec 4.6). */
@Service
public class AdminOverviewService {

	private final AuthService auth;
	private final AuthzService authz;
	private final FeatureAccessService features;
	private final JdbcClient jdbc;

	public AdminOverviewService(AuthService auth, AuthzService authz, FeatureAccessService features,
			JdbcClient jdbc) {
		this.auth = auth;
		this.authz = authz;
		this.features = features;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AdminOverview overview(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireStaff(actor);
		boolean superadmin = authz.hasRole(actor, AuthzService.SUPERADMIN);
		boolean moderation = superadmin || authz.hasRole(actor, AuthzService.MODERATOR);
		boolean accounts = superadmin || authz.hasRole(actor, AuthzService.SUPPORT);
		DbSession.setUser(jdbc, actor);
		return new AdminOverview(
				moderation ? moderation() : null,
				accounts ? accounts() : null,
				superadmin ? featureCounts(actor) : null,
				superadmin ? backup() : null);
	}

	private AdminOverview.Moderation moderation() {
		return new AdminOverview.Moderation(
				count("select count(*) from public.book_submissions where status = 'pending'"),
				count("select count(*) from public.forum_reports where status = 'pending'"));
	}

	private AdminOverview.Accounts accounts() {
		return new AdminOverview.Accounts(
				count("select count(*) from auth.users"),
				count("select count(*) from auth.users where created_at >= now() - interval '7 days'"));
	}

	private Map<String, Integer> featureCounts(UUID actor) {
		Map<String, Integer> out = new LinkedHashMap<>();
		features.catalog(actor).forEach(entry -> out.put(entry.feature(), (int) entry.enabledCount()));
		return out;
	}

	/** Último backup de qualquer status; atrasado se não há sucesso nas últimas 26 horas. */
	private AdminOverview.Backup backup() {
		boolean stale = Boolean.TRUE.equals(jdbc.sql("""
				select not exists (
				  select 1 from public.backup_runs
				  where status = 'success' and finished_at >= now() - interval '26 hours')
				""").query(Boolean.class).single());
		return jdbc.sql("""
				select status, finished_at, size_bytes from public.backup_runs
				order by started_at desc, id desc limit 1
				""")
				.query((rs, rowNum) -> {
					OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
					long size = rs.getLong("size_bytes");
					return new AdminOverview.Backup(rs.getString("status"),
							finished == null ? null : finished.toInstant(),
							rs.wasNull() ? null : size, stale);
				})
				.optional()
				.orElse(new AdminOverview.Backup(null, null, null, true));
	}

	private int count(String sql) {
		return jdbc.sql(sql).query(Long.class).single().intValue();
	}
}
