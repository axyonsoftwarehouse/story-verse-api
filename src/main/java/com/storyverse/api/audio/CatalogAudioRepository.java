package com.storyverse.api.audio;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

@Repository
public class CatalogAudioRepository {

	private final JdbcClient jdbc;

	public CatalogAudioRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> listEditions(UUID bookId) {
		return DbRows.list(jdbc, """
				select a.*, b.book_key, b.title as book_title, b.cover_url as book_cover_url
				  from public.catalog_book_audio a
				  join public.catalog_books b on b.id = a.book_id
				 where a.book_id = :book
				 order by a.created_at, a.id
				""", Map.of("book", bookId));
	}

	public Optional<Map<String, Object>> findEdition(UUID audioId) {
		return DbRows.one(jdbc, """
				select a.*, b.book_key, b.title as book_title, b.cover_url as book_cover_url
				  from public.catalog_book_audio a
				  join public.catalog_books b on b.id = a.book_id
				 where a.id = :id
				""", Map.of("id", audioId));
	}

	public List<Map<String, Object>> listChapters(UUID audioId) {
		return DbRows.list(jdbc, """
				select *
				  from public.catalog_audio_chapters
				 where audio_id = :audio
				 order by chapter_index
				""", Map.of("audio", audioId));
	}

	public Optional<Map<String, Object>> findChapter(UUID audioId, int chapterIndex) {
		return DbRows.one(jdbc, """
				select *
				  from public.catalog_audio_chapters
				 where audio_id = :audio and chapter_index = :chapter
				""", Map.of("audio", audioId, "chapter", chapterIndex));
	}

	public Optional<Map<String, Object>> insertEdition(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.catalog_book_audio
					(id, book_id, language, narrator, source, source_url, rights, rights_note,
					 license_note, text_mapping, cover_url, created_by)
				values
					(:id, :book_id, :language, :narrator, :source, :source_url, :rights, :rights_note,
					 :license_note, :text_mapping, :cover_url, :created_by)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> updateEdition(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_book_audio
				   set language = :language,
				       narrator = :narrator,
				       source = :source,
				       source_url = :source_url,
				       rights = :rights,
				       rights_note = :rights_note,
				       license_note = :license_note,
				       text_mapping = :text_mapping,
				       cover_url = :cover_url,
				       updated_at = now()
				 where id = :id
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> setStatus(UUID id, String status, boolean publish) {
		return DbRows.one(jdbc, """
				update public.catalog_book_audio
				   set status = :status,
				       published_at = case when :publish then coalesce(published_at, now()) else published_at end,
				       updated_at = now()
				 where id = :id
				 returning *
				""", Map.of("id", id, "status", status, "publish", publish));
	}

	public int deleteEdition(UUID id) {
		return DbRows.update(jdbc, "delete from public.catalog_book_audio where id = :id", Map.of("id", id));
	}

	public Optional<Map<String, Object>> insertChapter(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				insert into public.catalog_audio_chapters
					(audio_id, chapter_index, title, audio_path, mime_type, duration_seconds,
					 start_seconds, end_seconds, size_bytes, checksum, text_chapter_index,
					 text_chapter_end_index, status)
				values
					(:audio_id, :chapter_index, :title, :audio_path, :mime_type, :duration_seconds,
					 :start_seconds, :end_seconds, :size_bytes, :checksum, :text_chapter_index,
					 :text_chapter_end_index, :status)
				returning *
				""", params);
	}

	public Optional<Map<String, Object>> updateChapter(Map<String, Object> params) {
		return DbRows.one(jdbc, """
				update public.catalog_audio_chapters
				   set title = :title,
				       text_chapter_index = :text_chapter_index,
				       text_chapter_end_index = :text_chapter_end_index,
				       start_seconds = :start_seconds,
				       end_seconds = :end_seconds,
				       duration_seconds = :duration_seconds,
				       updated_at = now()
				 where audio_id = :audio_id and chapter_index = :chapter_index
				 returning *
				""", params);
	}

	public Optional<Map<String, Object>> recalculate(UUID audioId) {
		return DbRows.one(jdbc, """
				update public.catalog_book_audio a
				   set total_duration_seconds = coalesce(
				         (select sum(c.duration_seconds) from public.catalog_audio_chapters c
				           where c.audio_id = a.id), 0),
				       chapter_count = coalesce(
				         (select count(*) from public.catalog_audio_chapters c
				           where c.audio_id = a.id), 0),
				       updated_at = now()
				 where a.id = :id
				 returning *
				""", Map.of("id", audioId));
	}
}
