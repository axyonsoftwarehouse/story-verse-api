package com.storyverse.api.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

/** Coleções curadas do catálogo (V17). As linhas guardam só a ordem; os livros vivem no catálogo. */
@Repository
public class CatalogCollectionRepository {

	private final JdbcClient jdbc;

	public CatalogCollectionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> listAll() {
		return DbRows.list(jdbc, """
				select c.id, c.slug, c.title, c.description, c.status, c.sort_order, c.created_at, c.updated_at,
				       (select count(*) from public.catalog_collection_books cb
				         where cb.collection_id = c.id) as book_count
				  from public.catalog_collections c
				 order by c.sort_order, lower(c.title), c.id
				""", Map.of());
	}

	public Optional<Map<String, Object>> findById(UUID id) {
		return DbRows.one(jdbc, "select * from public.catalog_collections where id = :id", Map.of("id", id));
	}

	public Optional<Map<String, Object>> findBySlug(String slug) {
		return DbRows.one(jdbc, "select * from public.catalog_collections where slug = :slug", Map.of("slug", slug));
	}

	public Optional<Map<String, Object>> insert(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.catalog_collections (id, slug, title, description, status, sort_order)
				values (:id, :slug, :title, :description, :status, :sort_order)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> update(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_collections
				   set slug = :slug,
				       title = :title,
				       description = :description,
				       status = :status,
				       sort_order = :sort_order,
				       updated_at = now()
				 where id = :id
				 returning *
				""", params);
	}

	public int delete(UUID id) {
		return DbRows.update(jdbc, "delete from public.catalog_collections where id = :id", Map.of("id", id));
	}

	public List<UUID> bookIds(UUID collectionId) {
		return jdbc.sql("""
				select book_id from public.catalog_collection_books
				 where collection_id = :id order by sort_order, book_id
				""").param("id", collectionId).query(UUID.class).list();
	}

	public void replaceBooks(UUID collectionId, List<UUID> ids) {
		DbRows.update(jdbc, "delete from public.catalog_collection_books where collection_id = :id",
				Map.of("id", collectionId));
		int order = 0;
		for (UUID bookId : ids) {
			addBook(collectionId, bookId, order++);
		}
	}

	public void addBook(UUID collectionId, UUID bookId, int sortOrder) {
		DbRows.update(jdbc, """
				insert into public.catalog_collection_books (collection_id, book_id, sort_order)
				values (:collection, :book, :sort)
				on conflict (collection_id, book_id) do update set sort_order = excluded.sort_order
				""", Map.of("collection", collectionId, "book", bookId, "sort", sortOrder));
	}

	/** Coleções publicadas, com a contagem de livros publicados. */
	public List<Map<String, Object>> listPublished() {
		return DbRows.list(jdbc, """
				select c.id, c.slug, c.title, c.description, c.sort_order,
				       (select count(*) from public.catalog_collection_books cb
				          join public.catalog_books b on b.id = cb.book_id
				         where cb.collection_id = c.id and b.status = 'published') as book_count
				  from public.catalog_collections c
				 where c.status = 'published'
				 order by c.sort_order, lower(c.title), c.id
				""", Map.of());
	}

	/** Livros publicados de uma coleção, na ordem curada. */
	public List<Map<String, Object>> publishedBooks(UUID collectionId) {
		return DbRows.list(jdbc, """
				select b.book_key, b.title, b.author, b.genre, b.language, b.series, b.series_index,
				       b.description, b.page_count, b.cover_url, b.featured, b.published_at
				  from public.catalog_collection_books cb
				  join public.catalog_books b on b.id = cb.book_id
				 where cb.collection_id = :id and b.status = 'published'
				 order by cb.sort_order, lower(b.title), b.id
				""", Map.of("id", collectionId));
	}

	/** Coleções publicadas em que o livro aparece (para a ficha pública). */
	public List<Map<String, Object>> collectionsForBookKey(String bookKey) {
		return DbRows.list(jdbc, """
				select c.id, c.slug, c.title
				  from public.catalog_collection_books cb
				  join public.catalog_collections c on c.id = cb.collection_id
				  join public.catalog_books b on b.id = cb.book_id
				 where b.book_key = :key and c.status = 'published'
				 order by c.sort_order, lower(c.title)
				""", Map.of("key", bookKey));
	}

	/** Livros de uma coleção para o admin (todos os status). */
	public List<Map<String, Object>> adminBooks(UUID collectionId) {
		return DbRows.list(jdbc, """
				select b.id, b.book_key, b.title, b.author, b.status, b.source
				  from public.catalog_collection_books cb
				  join public.catalog_books b on b.id = cb.book_id
				 where cb.collection_id = :id
				 order by cb.sort_order, lower(b.title), b.id
				""", Map.of("id", collectionId));
	}
}
