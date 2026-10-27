package com.storyverse.api.admin;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

/** Usuários ativos a partir de {@code activity_days}: superadmin apenas, cache curto de 5 minutos. */
@Service
public class AdminActiveService {

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ShortCache cache = new ShortCache(Duration.ofMinutes(5));

	public AdminActiveService(AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AdminActive active(String token, String from, String to) {
		requireMetricsAccess(token);
		AdminRange range = AdminRange.parse(from, to);
		return cache.get("active|" + range.from() + "|" + range.to(), () -> load(range));
	}

	private AdminActive load(AdminRange range) {
		List<AdminActive.Point> series = jdbc.sql("select day, active from public.admin_metrics_active_daily(:from, :to)")
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminActive.Point(rs.getObject("day", LocalDate.class), rs.getLong("active")))
				.list();
		return jdbc.sql("select dau, wau, mau from public.admin_metrics_active_summary(:to)")
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminActive(rs.getLong("dau"), rs.getLong("wau"), rs.getLong("mau"), series))
				.single();
	}

	private UUID requireMetricsAccess(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		return actor;
	}
}
