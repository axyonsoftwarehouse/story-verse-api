package com.storyverse.api.admin;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

/** Métricas de produto: superadmin apenas, agregados e cache curto de 5 minutos. */
@Service
public class AdminMetricsService {

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ShortCache cache = new ShortCache(Duration.ofMinutes(5));

	public AdminMetricsService(AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AdminMetrics overview(String token, String from, String to) {
		requireMetricsAccess(token);
		AdminRange range = AdminRange.parse(from, to);
		String key = "overview|" + range.from() + "|" + range.to();
		AdminMetrics cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		AdminMetrics.Accounts accounts = jdbc.sql("""
				select total, new24h, new_today, new7, new30, confirmed, staff
				  from public.admin_metrics_accounts()
				""").query((rs, rowNum) -> new AdminMetrics.Accounts(
						rs.getLong("total"), rs.getLong("new24h"), rs.getLong("new_today"),
						rs.getLong("new7"), rs.getLong("new30"),
						rs.getLong("confirmed"), rs.getLong("staff"))).single();
		AdminMetrics.Reading reading = readingSummary(range);
		Map<String, Long> features = new LinkedHashMap<>();
		jdbc.sql("select feature, enabled from public.admin_metrics_features()")
				.query((rs, rowNum) -> Map.entry(rs.getString("feature"), rs.getLong("enabled")))
				.list().forEach(entry -> features.put(entry.getKey(), entry.getValue()));
		AdminMetrics result = new AdminMetrics(accounts, reading, features, retentionRows(range));
		cache.put(key, result);
		return result;
	}

	@Transactional(readOnly = true)
	public List<AdminMetricsReadingPoint> readingSeries(String token, String from, String to, String group) {
		requireMetricsAccess(token);
		AdminRange range = AdminRange.parse(from, to);
		String bucket = AdminRange.group(group);
		String key = "reading|" + bucket + "|" + range.from() + "|" + range.to();
		List<AdminMetricsReadingPoint> cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		String sql = bucket.equals("week")
				? "select bucket, active_readers, seconds, books_started, books_finished, signups, pages from public.admin_metrics_weekly(:from, :to)"
				: "select day as bucket, active_readers, seconds, books_started, books_finished, signups, pages from public.admin_metrics_daily(:from, :to)";
		List<AdminMetricsReadingPoint> rows = jdbc.sql(sql)
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminMetricsReadingPoint(
						rs.getObject("bucket", LocalDate.class),
						rs.getLong("active_readers"),
						Math.round(rs.getLong("seconds") / 60.0),
						rs.getLong("books_started"),
						rs.getLong("books_finished"),
						rs.getLong("signups"),
						rs.getLong("pages")))
				.list();
		cache.put(key, rows);
		return rows;
	}

	@Transactional(readOnly = true)
	public List<AdminMetrics.Retention> retention(String token, String from, String to) {
		requireMetricsAccess(token);
		AdminRange range = AdminRange.parse(from, to);
		String key = "retention|" + range.from() + "|" + range.to();
		List<AdminMetrics.Retention> cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		List<AdminMetrics.Retention> rows = retentionRows(range);
		cache.put(key, rows);
		return rows;
	}

	private AdminMetrics.Reading readingSummary(AdminRange range) {
		return jdbc.sql("""
				select active_readers, seconds, books_started, books_finished, entries_created, pages
				  from public.admin_metrics_reading(:from, :to)
				""")
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminMetrics.Reading(
						rs.getLong("active_readers"),
						Math.round(rs.getLong("seconds") / 60.0),
						rs.getLong("books_started"),
						rs.getLong("books_finished"),
						rs.getLong("entries_created"),
						rs.getLong("pages")))
				.single();
	}

	private List<AdminMetrics.Retention> retentionRows(AdminRange range) {
		return jdbc.sql("""
				select cohort_week, cohort_size, d1, d7, d30
				  from public.admin_metrics_retention(:from, :to)
				""")
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminMetrics.Retention(
						rs.getObject("cohort_week", LocalDate.class),
						rs.getLong("cohort_size"), rs.getLong("d1"), rs.getLong("d7"), rs.getLong("d30")))
				.list();
	}

	private UUID requireMetricsAccess(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		return actor;
	}
}
