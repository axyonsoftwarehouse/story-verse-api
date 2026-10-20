package com.storyverse.api.rpc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbErrors;
import com.storyverse.api.common.DbRows;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.json.JsonMapper;

/**
 * "Tradutor" do professor: por transação, informa ao banco qual usuário está pedindo e chama a
 * função. Só passa por aqui uma **lista branca** — nunca as internas (ex.: apply_diamonds).
 */
@Service
public class RpcService {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final JdbcClient jdbc;

	public RpcService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public Object call(UUID userId, String name, Map<String, Object> args) {
		DbSession.setUser(jdbc, userId);
		try {
			return switch (name) {
				case "reading_heartbeat" -> scalar("select public.reading_heartbeat(:active)",
						Map.of("active", boolArg(args, "p_active")));
				case "my_wallet" -> DbRows.list(jdbc, "select * from public.my_wallet()", Map.of());
				case "my_diamond_history" -> DbRows.list(jdbc, "select * from public.my_diamond_history(p_limit => :limit)",
						Map.of("limit", intArg(args, "p_limit", 30)));
				case "my_streak_freezes" -> scalars("select * from public.my_streak_freezes()");
				case "claim_reading_rewards" -> DbRows.list(jdbc, "select * from public.claim_reading_rewards()", Map.of());
				case "daily_spin_status" -> DbRows.list(jdbc, "select * from public.daily_spin_status()", Map.of());
				case "spin_daily_wheel" -> DbRows.list(jdbc, "select * from public.spin_daily_wheel()", Map.of());
				case "use_streak_freeze" -> scalar("select public.use_streak_freeze(cast(:days as date[]))",
						Map.of("days", dateArray(args, "p_days")));
				case "report_reading" -> {
					voidCall("select public.report_reading(cast(:days as jsonb))", Map.of("days", jsonArg(args, "p_days")));
					yield null;
				}
				case "weekly_ranking" -> DbRows.list(jdbc, "select * from public.weekly_ranking(p_offset => :offset, p_limit => :limit)",
						Map.of("offset", intArg(args, "p_offset", 0), "limit", intArg(args, "p_limit", 50)));
				case "my_ranking_profile" -> DbRows.list(jdbc, "select * from public.my_ranking_profile()", Map.of());
				case "my_weekly_awards" -> DbRows.list(jdbc, "select * from public.my_weekly_awards()", Map.of());
				case "gift_rules" -> DbRows.list(jdbc, "select * from public.gift_rules()", Map.of());
				case "gift_catalog" -> DbRows.list(jdbc, "select * from public.gift_catalog()", Map.of());
				case "send_gift" -> DbRows.list(jdbc, """
						select * from public.send_gift(
						  cast(:target as uuid), :gift, :ref
						)
						""", Map.of(
						"target", strArg(args, "p_to"),
						"gift", strArg(args, "p_gift"),
						"ref", strArg(args, "p_ref")));
				case "my_gifts_received" -> DbRows.list(jdbc,
						"select * from public.my_gifts_received(p_limit => :limit)",
						Map.of("limit", intArg(args, "p_limit", 20)));
				case "my_gift_summary" -> DbRows.list(jdbc, "select * from public.my_gift_summary()", Map.of());
				case "weekly_gift_counts" -> DbRows.list(jdbc,
						"select * from public.weekly_gift_counts(p_offset => :offset)",
						Map.of("offset", intArg(args, "p_offset", 0)));
				case "ai_pack_catalog" -> DbRows.list(jdbc, "select * from public.ai_pack_catalog()", Map.of());
				case "buy_ai_pack" -> DbRows.list(jdbc,
						"select * from public.buy_ai_pack(:pack, :ref)",
						Map.of(
								"pack", strArg(args, "p_pack"),
								"ref", strArg(args, "p_ref")));
				case "my_ai_quota" -> DbRows.list(jdbc,
						"select * from public.my_ai_quota(p_daily_limit => :limit)",
						Map.of("limit", intArg(args, "p_daily_limit", 50)));
				case "set_ranking_hidden" -> {
					voidCall("select public.set_ranking_hidden(cast(:hidden as boolean))", Map.of("hidden", boolArg(args, "p_hidden")));
					yield null;
				}
				case "mark_awards_seen" -> {
					voidCall("select public.mark_awards_seen()", Map.of());
					yield null;
				}
				case "is_admin" -> scalar("select public.is_admin()", Map.of());
				case "set_submission_cover" -> {
					voidCall("select public.set_submission_cover(cast(:id as uuid), cast(:cover as text))",
							Map.of("id", strArg(args, "submission_id"), "cover", strArg(args, "new_cover")));
					yield null;
				}
				default -> throw new AuthException(404, "Função não disponível.");
			};
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	private List<String> scalars(String sql) {
		return DbRows.list(jdbc, sql, Map.of()).stream()
				.map(row -> String.valueOf(row.values().iterator().next()))
				.toList();
	}

	private Object scalar(String sql, Map<String, Object> params) {
		var spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec.query((rs, rowNum) -> rs.getObject(1)).optional().orElse(null);
	}

	private void voidCall(String sql, Map<String, Object> params) {
		var spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		spec.query((rs, rowNum) -> null).optional();
	}

	private static int intArg(Map<String, Object> args, String key, int fallback) {
		Object value = args.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof String text) {
			try {
				return Integer.parseInt(text.trim());
			} catch (NumberFormatException ignored) {
				// usa o padrão
			}
		}
		return fallback;
	}

	private static boolean boolArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
	}

	private static String strArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		return value == null ? null : String.valueOf(value);
	}

	/** Monta um literal de array de datas para o Postgres: {"2026-10-10",…}. */
	private static String dateArray(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (!(value instanceof List<?> list) || list.isEmpty()) {
			return "{}";
		}
		StringBuilder literal = new StringBuilder("{");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				literal.append(',');
			}
			literal.append('"').append(String.valueOf(list.get(i)).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return literal.append('}').toString();
	}

	private static String jsonArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			return "{}";
		}
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception exception) {
			return "{}";
		}
	}
}
