package com.storyverse.api.admin;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

/** Métricas de produto: superadmin apenas, agregados e cache curto de 5 minutos. */
@Service
public class AdminMetricsService {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final int MAX_DAYS = 90;
	private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

	private record Range(LocalDate from, LocalDate to) {
	}

	private record Cached(Object value, long at) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

	public AdminMetricsService(AuthService auth, AuthzService authz, JdbcClient jdbc) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AdminMetrics overview(String token, String from, String to) {
		UUID actor = requireMetricsAccess(token);
		Range range = range(from, to);
		String key = "overview|" + range.from() + "|" + range.to();
		AdminMetrics cached = cached(key, AdminMetrics.class);
		if (cached != null) {
			return cached;
		}
		AdminMetrics.Accounts accounts = jdbc.sql("""
				select total, new7, new30, confirmed, staff
				  from public.admin_metrics_accounts()
				""").query((rs, rowNum) -> new AdminMetrics.Accounts(
						rs.getLong("total"), rs.getLong("new7"), rs.getLong("new30"),
						rs.getLong("confirmed"), rs.getLong("staff"))).single();
		AdminMetrics.Reading reading = readingSummary(range);
		Map<String, Long> features = new LinkedHashMap<>();
		jdbc.sql("select feature, enabled from public.admin_metrics_features()")
				.query((rs, rowNum) -> Map.entry(rs.getString("feature"), rs.getLong("enabled")))
				.list().forEach(entry -> features.put(entry.getKey(), entry.getValue()));
		AdminMetrics result = new AdminMetrics(accounts, reading, features, retentionRows(range));
		put(key, result);
		return result;
	}

	@Transactional(readOnly = true)
	public List<AdminMetricsReadingPoint> readingSeries(String token, String from, String to, String group) {
		requireMetricsAccess(token);
		Range range = range(from, to);
		String bucket = group == null || group.isBlank() ? "day" : group.strip();
		if (!bucket.equals("day") && !bucket.equals("week")) {
			throw new AuthException(400, "Agrupamento inválido. Use day ou week.");
		}
		String key = "reading|" + bucket + "|" + range.from() + "|" + range.to();
		@SuppressWarnings("unchecked")
		List<AdminMetricsReadingPoint> cached = cached(key, List.class);
		if (cached != null) {
			return cached;
		}
		String sql = bucket.equals("week")
				? "select bucket, active_readers, seconds, books_started, books_finished from public.admin_metrics_weekly(:from, :to)"
				: "select day as bucket, active_readers, seconds, books_started, books_finished from public.admin_metrics_daily(:from, :to)";
		List<AdminMetricsReadingPoint> rows = jdbc.sql(sql)
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminMetricsReadingPoint(
						rs.getObject("bucket", LocalDate.class),
						rs.getLong("active_readers"),
						Math.round(rs.getLong("seconds") / 60.0),
						rs.getLong("books_started"),
						rs.getLong("books_finished")))
				.list();
		put(key, rows);
		return rows;
	}

	@Transactional(readOnly = true)
	public List<AdminMetrics.Retention> retention(String token, String from, String to) {
		requireMetricsAccess(token);
		Range range = range(from, to);
		String key = "retention|" + range.from() + "|" + range.to();
		@SuppressWarnings("unchecked")
		List<AdminMetrics.Retention> cached = cached(key, List.class);
		if (cached != null) {
			return cached;
		}
		List<AdminMetrics.Retention> rows = retentionRows(range);
		put(key, rows);
		return rows;
	}

	private AdminMetrics.Reading readingSummary(Range range) {
		return jdbc.sql("""
				select active_readers, seconds, books_started, books_finished, entries_created
				  from public.admin_metrics_reading(:from, :to)
				""")
				.param("from", Date.valueOf(range.from()))
				.param("to", Date.valueOf(range.to()))
				.query((rs, rowNum) -> new AdminMetrics.Reading(
						rs.getLong("active_readers"),
						Math.round(rs.getLong("seconds") / 60.0),
						rs.getLong("books_started"),
						rs.getLong("books_finished"),
						rs.getLong("entries_created")))
				.single();
	}

	private List<AdminMetrics.Retention> retentionRows(Range range) {
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

	private Range range(String fromRaw, String toRaw) {
		LocalDate to = parseDate(toRaw, LocalDate.now(SAO_PAULO));
		LocalDate from = parseDate(fromRaw, to.minusDays(29));
		if (from.isAfter(to)) {
			throw new AuthException(400, "A data inicial precisa ser anterior à final.");
		}
		if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
			throw new AuthException(400, "O período máximo é de 90 dias.");
		}
		return new Range(from, to);
	}

	private static LocalDate parseDate(String raw, LocalDate fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return LocalDate.parse(raw.trim());
		} catch (DateTimeParseException error) {
			throw new AuthException(400, "Data inválida. Use AAAA-MM-DD.");
		}
	}

	private <T> T cached(String key, Class<T> type) {
		Cached entry = cache.get(key);
		if (entry == null) {
			return null;
		}
		if (System.currentTimeMillis() - entry.at() > CACHE_TTL_MS) {
			cache.remove(key);
			return null;
		}
		return type.cast(entry.value());
	}

	private void put(String key, Object value) {
		long now = System.currentTimeMillis();
		if (cache.size() > 100) {
			cache.entrySet().removeIf(entry -> now - entry.getValue().at() > CACHE_TTL_MS);
		}
		cache.put(key, new Cached(value, now));
	}
}
