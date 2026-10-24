package com.storyverse.api.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class CatalogRepository {

	private final JdbcClient jdbc;

	public CatalogRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> listPublished(String q, String genre, String language, String author,
			String collection, Boolean featured, String sort, int limit, int offset) {
		StringBuilder sql = new StringBuilder("""
				select b.book_key, b.title, b.author, b.genre, b.language, b.series, b.series_index,
				       b.description, b.page_count, b.cover_url, b.featured, b.published_at
				  from public.catalog_books b
				 where b.status = 'published'
				""");
		Map<String, Object> params = new LinkedHashMap<>();
		if (q != null && !q.isBlank()) {
			sql.append(" and (b.title ilike :q or coalesce(b.author, '') ilike :q"
					+ " or coalesce(b.description, '') ilike :q)");
			params.put("q", "%" + q.strip() + "%");
		}
		if (genre != null && !genre.isBlank()) {
			sql.append(" and b.genre = :genre");
			params.put("genre", genre.strip());
		}
		if (language != null && !language.isBlank()) {
			sql.append(" and b.language = :language");
			params.put("language", language.strip());
		}
		if (author != null && !author.isBlank()) {
			sql.append(" and coalesce(b.author, '') ilike :author");
			params.put("author", "%" + author.strip() + "%");
		}
		if (collection != null && !collection.isBlank()) {
			sql.append("""
					 and exists (select 1
					               from public.catalog_collection_books cb
					               join public.catalog_collections c on c.id = cb.collection_id
					              where cb.book_id = b.id and c.slug = :collection and c.status = 'published')
					""");
			params.put("collection", collection.strip());
		}
		if (featured != null) {
			sql.append(" and b.featured = :featured");
			params.put("featured", featured);
		}
		sql.append(switch (sort == null ? "" : sort) {
			case "title" -> " order by lower(b.title), b.id";
			case "author" -> " order by lower(coalesce(b.author, '')), lower(b.title), b.id";
			case "featured" -> " order by b.sort_order, b.published_at desc nulls last, b.id";
			default -> " order by b.published_at desc nulls last, b.id";
		});
		sql.append(" limit :limit offset :offset");
		params.put("limit", limit);
		params.put("offset", offset);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> findPublishedByKey(String bookKey) {
		return DbRows.one(jdbc, """
				select book_key, title, author, genre, language, series, series_index, description,
				       page_count, rights, rights_note, cover_url, featured, published_at
				  from public.catalog_books
				 where book_key = :key and status = 'published'
				""", Map.of("key", bookKey));
	}

	public Optional<Map<String, Object>> findTextByKey(String bookKey) {
		return DbRows.one(jdbc, """
				select storage_bucket, text_path
				  from public.catalog_books
				 where book_key = :key and status = 'published'
				""", Map.of("key", bookKey));
	}

	public List<Map<String, Object>> listAdmin(String status, String q, String genre, String language,
			String source, Boolean featured, int limit, int offset) {
		StringBuilder sql = new StringBuilder("select * from public.catalog_books b where true");
		Map<String, Object> params = new LinkedHashMap<>();
		if (status != null && !status.isBlank()) {
			sql.append(" and b.status = :status");
			params.put("status", status.strip());
		}
		if (q != null && !q.isBlank()) {
			sql.append(" and (b.title ilike :q or coalesce(b.author, '') ilike :q"
					+ " or b.book_key ilike :q)");
			params.put("q", "%" + q.strip() + "%");
		}
		if (genre != null && !genre.isBlank()) {
			sql.append(" and b.genre = :genre");
			params.put("genre", genre.strip());
		}
		if (language != null && !language.isBlank()) {
			sql.append(" and b.language = :language");
			params.put("language", language.strip());
		}
		if (source != null && !source.isBlank()) {
			sql.append(" and b.source = :source");
			params.put("source", source.strip());
		}
		if (featured != null) {
			sql.append(" and b.featured = :featured");
			params.put("featured", featured);
		}
		sql.append(" order by b.updated_at desc, b.id limit :limit offset :offset");
		params.put("limit", limit);
		params.put("offset", offset);
		return DbRows.list(jdbc, sql.toString(), params);
	}

	public Optional<Map<String, Object>> findById(UUID id) {
		return DbRows.one(jdbc, "select * from public.catalog_books where id = :id", Map.of("id", id));
	}

	public Optional<Map<String, Object>> findByChecksum(String checksum) {
		return DbRows.one(jdbc, """
				select id, book_key, title
				  from public.catalog_books
				 where checksum = :checksum
				 order by created_at
				 limit 1
				""", Map.of("checksum", checksum));
	}

	public Optional<Map<String, Object>> insert(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.catalog_books
					(id, book_key, source, submission_id, title, author, genre, language, series,
					 series_index, description, page_count, rights, rights_note, storage_bucket,
					 text_path, original_path, original_content_type, char_count, checksum, cover_url,
					 status, featured, sort_order, published_at, created_by)
				values
					(:id, :book_key, 'admin', null, :title, :author, :genre, :language, :series,
					 :series_index, :description, :page_count, :rights, :rights_note, 'catalog-books',
					 :text_path, :original_path, :original_content_type, :char_count, :checksum, :cover_url,
					 :status, :featured, :sort_order, :published_at, :created_by)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> update(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_books
				   set title = :title,
				       author = :author,
				       genre = :genre,
				       language = :language,
				       series = :series,
				       series_index = :series_index,
				       description = :description,
				       page_count = :page_count,
				       rights = :rights,
				       rights_note = :rights_note,
				       text_path = :text_path,
				       original_path = :original_path,
				       original_content_type = :original_content_type,
				       char_count = :char_count,
				       checksum = :checksum,
				       cover_url = :cover_url,
				       status = :status,
				       featured = :featured,
				       sort_order = :sort_order,
				       published_at = :published_at,
				       updated_at = now()
				 where id = :id
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> updateText(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_books
				   set text_path = :text_path,
				       original_path = :original_path,
				       original_content_type = :original_content_type,
				       char_count = :char_count,
				       page_count = :page_count,
				       checksum = :checksum,
				       updated_at = now()
				 where id = :id
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> setStatus(UUID id, String status, boolean publish) {
		return DbRows.one(jdbc, """
				update public.catalog_books
				   set status = :status,
				       published_at = case when :publish then coalesce(published_at, now()) else published_at end,
				       updated_at = now()
				 where id = :id
				 returning *
				""", Map.of("id", id, "status", status, "publish", publish));
	}

	public int delete(UUID id) {
		return DbRows.update(jdbc, "delete from public.catalog_books where id = :id", Map.of("id", id));
	}

	public Optional<Map<String, Object>> promoteApproved(UUID submissionId) {
		return DbRows.one(jdbc, """
				insert into public.catalog_books
					(book_key, source, submission_id, title, author, language, rights, rights_note,
					 storage_bucket, text_path, char_count, cover_url, status, published_at, created_by)
				select 'cm:' || s.id, 'community', s.id, s.title, s.author, s.language, s.rights,
				       s.rights_note, 'community-books', s.text_path, s.char_count, s.cover_url,
				       'published', coalesce(s.reviewed_at, now()), s.reviewed_by
				  from public.book_submissions s
				 where s.id = :id and s.status = 'approved'
				on conflict (book_key) do update
				   set title = excluded.title,
				       author = excluded.author,
				       rights = excluded.rights,
				       rights_note = excluded.rights_note,
				       char_count = excluded.char_count,
				       cover_url = coalesce(excluded.cover_url, catalog_books.cover_url),
				       status = 'published',
				       published_at = coalesce(catalog_books.published_at, excluded.published_at),
				       updated_at = now()
				 returning *
				""", Map.of("id", submissionId));
	}
}
