package com.storyverse.api.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LibraryRepository {

	static final String COLUMNS = """
			book_key, source, status, rating, review, started_on, finished_on, dnf_reason, dnf_note,
			progress_percent, progress_label, title, author, cover_url, series, series_index, genre, language,
			updated_at::text as updated_at, version, deleted_at::text as deleted_at
			""";

	/** Campos que uma escrita substitui (mesma ordem de LibraryEntry). */
	private static final String SET_FIELDS = """
			source = :source, status = :status, rating = :rating, review = :review,
			started_on = :startedOn, finished_on = :finishedOn, dnf_reason = :dnfReason, dnf_note = :dnfNote,
			progress_percent = :progressPercent, progress_label = :progressLabel, title = :title,
			author = :author, cover_url = :coverUrl, series = :series, series_index = :seriesIndex,
			genre = :genre, language = :language,
			updated_at = now(), version = version + 1, deleted_at = null
			""";

	private final JdbcClient jdbc;

	public LibraryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Inclui apagadas (`deletedAt` preenchido). */
	public Optional<LibraryEntry> find(UUID userId, String bookKey) {
		return jdbc.sql("select " + COLUMNS + " from public.library_entries where user_id = :userId and book_key = :bookKey")
				.param("userId", userId)
				.param("bookKey", bookKey)
				.query(LibraryRepository::map)
				.optional();
	}

	public int countLive(UUID userId) {
		return jdbc.sql("select count(*) from public.library_entries where user_id = :userId and deleted_at is null")
				.param("userId", userId)
				.query(Integer.class)
				.single();
	}

	/** Primeira gravação do livro. Vazio se outro aparelho criou a linha antes. */
	public Optional<LibraryEntry> insert(UUID userId, LibraryEntry e) {
		return bind(jdbc.sql("""
				insert into public.library_entries
					(user_id, book_key, source, status, rating, review, started_on, finished_on, dnf_reason, dnf_note,
					 progress_percent, progress_label, title, author, cover_url, series, series_index, genre, language)
				values (:userId, :bookKey, :source, :status, :rating, :review, :startedOn, :finishedOn, :dnfReason,
					 :dnfNote, :progressPercent, :progressLabel, :title, :author, :coverUrl, :series, :seriesIndex,
					 :genre, :language)
				on conflict (user_id, book_key) do nothing
				returning\s""" + COLUMNS), userId, e)
				.query(LibraryRepository::map)
				.optional();
	}

	/** Substitui só se a linha viva ainda estiver como o cliente leu. Vazio se mudou. */
	public Optional<LibraryEntry> replaceIfMatches(UUID userId, LibraryEntry e, String baseUpdatedAt) {
		return bind(jdbc.sql("update public.library_entries set " + SET_FIELDS + """
				 where user_id = :userId and book_key = :bookKey and deleted_at is null
				   and updated_at::text = :base
				returning\s""" + COLUMNS), userId, e)
				.param("base", baseUpdatedAt)
				.query(LibraryRepository::map)
				.optional();
	}

	/** Reaproveita uma linha apagada (o livro volta para a estante). Vazio se não estava apagada. */
	public Optional<LibraryEntry> revive(UUID userId, LibraryEntry e) {
		return bind(jdbc.sql("update public.library_entries set " + SET_FIELDS + """
				 where user_id = :userId and book_key = :bookKey and deleted_at is not null
				returning\s""" + COLUMNS), userId, e)
				.query(LibraryRepository::map)
				.optional();
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID userId, LibraryEntry e) {
		return spec.param("userId", userId)
				.param("bookKey", e.bookKey())
				.param("source", e.source())
				.param("status", e.status())
				.param("rating", e.rating())
				.param("review", e.review())
				.param("startedOn", e.startedOn())
				.param("finishedOn", e.finishedOn())
				.param("dnfReason", e.dnfReason())
				.param("dnfNote", e.dnfNote())
				.param("progressPercent", e.progressPercent())
				.param("progressLabel", e.progressLabel())
				.param("title", e.title())
				.param("author", e.author())
				.param("coverUrl", e.coverUrl())
				.param("series", e.series())
				.param("seriesIndex", e.seriesIndex())
				.param("genre", e.genre())
				.param("language", e.language());
	}

	static LibraryEntry map(ResultSet rs, int rowNum) throws SQLException {
		return new LibraryEntry(
				rs.getString("book_key"),
				rs.getString("source"),
				rs.getString("status"),
				rs.getBigDecimal("rating"),
				rs.getString("review"),
				rs.getObject("started_on", LocalDate.class),
				rs.getObject("finished_on", LocalDate.class),
				rs.getString("dnf_reason"),
				rs.getString("dnf_note"),
				rs.getObject("progress_percent", Integer.class),
				rs.getString("progress_label"),
				rs.getString("title"),
				rs.getString("author"),
				rs.getString("cover_url"),
				rs.getString("series"),
				rs.getBigDecimal("series_index"),
				rs.getString("genre"),
				rs.getString("language"),
				rs.getString("updated_at"),
				rs.getInt("version"),
				rs.getString("deleted_at"));
	}
}
