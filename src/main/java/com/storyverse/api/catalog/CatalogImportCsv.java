package com.storyverse.api.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.storyverse.api.auth.AuthException;

/**
 * Leitor mínimo do `metadata.csv` da importação em lote. Aceita aspas, vírgulas e quebras de
 * linha dentro de campos (o suficiente para resumos e nomes); não é um CSV completo de biblioteca.
 */
final class CatalogImportCsv {

	static final int MAX_BYTES = 2 * 1024 * 1024;
	static final int MAX_ROWS = 500;
	private static final List<String> REQUIRED = List.of("file", "rights");

	private CatalogImportCsv() {
	}

	static List<Map<String, String>> parse(String csv) {
		if (csv == null || csv.isBlank()) throw new AuthException(400, "O metadata.csv está vazio.");
		if (csv.length() > MAX_BYTES) throw new AuthException(413, "O metadata.csv é grande demais.");
		String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
		List<List<String>> rows = read(text);
		if (rows.isEmpty()) throw new AuthException(400, "O metadata.csv está vazio.");

		List<String> header = new ArrayList<>();
		for (String value : rows.get(0)) header.add(value.trim().toLowerCase(Locale.ROOT));
		for (String required : REQUIRED) {
			if (!header.contains(required)) {
				throw new AuthException(400, "O metadata.csv precisa da coluna \"" + required + "\".");
			}
		}
		if (rows.size() - 1 > MAX_ROWS) throw new AuthException(413, "Um lote aceita no máximo 500 livros.");

		List<Map<String, String>> out = new ArrayList<>();
		for (int index = 1; index < rows.size(); index++) {
			List<String> values = rows.get(index);
			Map<String, String> row = new LinkedHashMap<>();
			for (int column = 0; column < header.size(); column++) {
				String key = header.get(column);
				if (key.isEmpty()) continue;
				row.put(key, column < values.size() ? values.get(column).trim() : "");
			}
			if (row.values().stream().allMatch(String::isBlank)) continue;
			row.put("_line", String.valueOf(index + 1));
			out.add(row);
		}
		return out;
	}

	private static List<List<String>> read(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean any = false;
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			if (quoted) {
				if (current == '"') {
					if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
						field.append('"');
						index++;
					} else {
						quoted = false;
					}
				} else {
					field.append(current);
				}
				continue;
			}
			switch (current) {
				case '"' -> {
					quoted = true;
					any = true;
				}
				case ',' -> {
					row.add(field.toString());
					field.setLength(0);
					any = true;
				}
				case '\n' -> {
					row.add(field.toString());
					field.setLength(0);
					rows.add(row);
					row = new ArrayList<>();
					any = false;
				}
				case '\r' -> {
					// CRLF: o \n seguinte fecha a linha.
				}
				default -> {
					field.append(current);
					any = true;
				}
			}
		}
		if (quoted) throw new AuthException(400, "O metadata.csv tem aspas não fechadas.");
		if (any || field.length() > 0 || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row);
		}
		return rows;
	}
}
