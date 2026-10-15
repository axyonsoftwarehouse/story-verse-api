package com.storyverse.api.rpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;

import tools.jackson.databind.json.JsonMapper;

/**
 * "Tradutor" do professor: por transação, informa ao banco qual usuário está pedindo
 * (`set_config('app.user_id', …, true)`) e chama a função. Só passa por aqui uma **lista branca**
 * de funções — nunca as internas (ex.: apply_diamonds), que a API chama por dentro.
 */
@Service
public class RpcService {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final ColumnMapRowMapper ROW_MAPPER = new ColumnMapRowMapper();

	private final JdbcClient jdbc;

	public RpcService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public Object call(UUID userId, String name, Map<String, Object> args) {
		jdbc.sql("select set_config('app.user_id', :uid, true)")
				.param("uid", userId.toString())
				.query((rs, rowNum) -> null)
				.optional();
		try {
			return switch (name) {
				case "my_wallet" -> rows("select * from public.my_wallet()", Map.of());
				case "my_diamond_history" -> rows("select * from public.my_diamond_history(p_limit => :limit)",
						Map.of("limit", intArg(args, "p_limit", 30)));
				case "my_streak_freezes" -> scalars("select * from public.my_streak_freezes()");
				case "claim_reading_rewards" -> rows("select * from public.claim_reading_rewards()", Map.of());
				case "daily_spin_status" -> rows("select * from public.daily_spin_status()", Map.of());
				case "spin_daily_wheel" -> rows("select * from public.spin_daily_wheel()", Map.of());
				case "use_streak_freeze" -> scalar("select public.use_streak_freeze(cast(:days as date[]))",
						Map.of("days", dateArray(args, "p_days")));
				case "report_reading" -> {
					voidCall("select public.report_reading(cast(:days as jsonb))", Map.of("days", jsonArg(args, "p_days")));
					yield null;
				}
				case "weekly_ranking" -> rows("select * from public.weekly_ranking(p_offset => :offset, p_limit => :limit)",
						Map.of("offset", intArg(args, "p_offset", 0), "limit", intArg(args, "p_limit", 50)));
				case "my_ranking_profile" -> rows("select * from public.my_ranking_profile()", Map.of());
				case "my_weekly_awards" -> rows("select * from public.my_weekly_awards()", Map.of());
				case "set_ranking_hidden" -> {
					voidCall("select public.set_ranking_hidden(cast(:hidden as boolean))", Map.of("hidden", boolArg(args, "p_hidden")));
					yield null;
				}
				case "mark_awards_seen" -> {
					voidCall("select public.mark_awards_seen()", Map.of());
					yield null;
				}
				default -> throw new AuthException(404, "Função não disponível.");
			};
		} catch (DataAccessException exception) {
			throw new AuthException(400, dbMessage(exception));
		}
	}

	private List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
		var spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec.query(ROW_MAPPER).list().stream().map(RpcService::normalizeRow).toList();
	}

	private List<String> scalars(String sql) {
		return rows(sql, Map.of()).stream()
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

	private static Map<String, Object> normalizeRow(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((key, value) -> out.put(key, normalize(value)));
		return out;
	}

	/** Datas viram texto ISO (o resto passa como veio). */
	private static Object normalize(Object value) {
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toInstant().toString();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().toString();
		}
		if (value instanceof java.sql.Time time) {
			return time.toLocalTime().toString();
		}
		return value;
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

	/** Mensagem do RAISE EXCEPTION do Postgres, sem o prefixo "ERROR:". */
	private static String dbMessage(DataAccessException exception) {
		Throwable cause = exception.getMostSpecificCause();
		String message = cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
		if (message == null) {
			return "Não foi possível concluir a ação.";
		}
		return message.replaceFirst("^ERROR:\\s*", "").trim();
	}
}
