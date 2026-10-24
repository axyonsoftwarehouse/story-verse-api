package com.storyverse.api.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

/** Registro das importações em lote e dos itens do relatório. */
@Repository
public class CatalogImportRepository {

	private final JdbcClient jdbc;

	public CatalogImportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Map<String, Object>> insert(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.catalog_imports (id, created_by, status, file_name, total)
				values (:id, :created_by, :status, :file_name, :total)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> update(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_imports
				   set status = :status,
				       total = :total,
				       imported = :imported,
				       skipped = :skipped,
				       error = :error,
				       finished_at = case when :status in ('done', 'failed') then now() else finished_at end
				 where id = :id
				 returning *
				""", params);
	}

	public List<Map<String, Object>> list() {
		return DbRows.list(jdbc, """
				select id, status, file_name, total, imported, skipped, error, created_at, finished_at
				  from public.catalog_imports
				 order by created_at desc
				 limit 30
				""", Map.of());
	}

	public Optional<Map<String, Object>> find(UUID id) {
		return DbRows.one(jdbc, "select * from public.catalog_imports where id = :id", Map.of("id", id));
	}

	public void insertItem(Map<String, Object> params) {
		DbRows.update(jdbc, """
				insert into public.catalog_import_items
					(import_id, line, file_name, status, message, catalog_book_id)
				values (:import, :line, :file, :status, :message, :book)
				""", params);
	}

	public List<Map<String, Object>> items(UUID id) {
		return DbRows.list(jdbc, """
				select line, file_name, status, message, catalog_book_id
				  from public.catalog_import_items
				 where import_id = :id
				 order by line nulls last, created_at
				""", Map.of("id", id));
	}

	public List<Map<String, Object>> errors(UUID id) {
		return DbRows.list(jdbc, """
				select line, file_name, status, message
				  from public.catalog_import_items
				 where import_id = :id and status in ('error', 'skipped')
				 order by line nulls last, created_at
				""", Map.of("id", id));
	}
}
