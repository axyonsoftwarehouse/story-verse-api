package com.storyverse.api.common;

import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ajuda a ler linhas como mapas (colunas em snake_case) com datas viram texto ISO. */
public final class DbRows {

	private static final ColumnMapRowMapper MAPPER = new ColumnMapRowMapper();

	private DbRows() {
	}

	public static List<Map<String, Object>> list(JdbcClient jdbc, String sql, Map<String, Object> params) {
		return spec(jdbc, sql, params).query(MAPPER).list().stream().map(DbRows::normalize).toList();
	}

	public static Optional<Map<String, Object>> one(JdbcClient jdbc, String sql, Map<String, Object> params) {
		return spec(jdbc, sql, params).query(MAPPER).optional().map(DbRows::normalize);
	}

	public static int update(JdbcClient jdbc, String sql, Map<String, Object> params) {
		return spec(jdbc, sql, params).update();
	}

	private static JdbcClient.StatementSpec spec(JdbcClient jdbc, String sql, Map<String, Object> params) {
		var spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec;
	}

	private static Map<String, Object> normalize(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((key, value) -> out.put(key, normalizeValue(value)));
		return out;
	}

	private static Object normalizeValue(Object value) {
		if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
		if (value instanceof Date date) return date.toLocalDate().toString();
		if (value instanceof Time time) return time.toLocalTime().toString();
		if (value instanceof Array array) return arrayToList(array);
		return value;
	}

	/** Arrays do Postgres (ex.: text[]) viram listas, para o JSON sair como array. */
	private static List<Object> arrayToList(Array array) {
		try {
			return Arrays.asList((Object[]) array.getArray());
		} catch (SQLException exception) {
			throw new IllegalStateException("Não foi possível ler um array do banco.", exception);
		}
	}
}
