package com.storyverse.api.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
}
