package com.storyverse.api.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/** Linha viva travada até o fim da transação (concluir/abandonar sem baseUpdatedAt). */
	public Optional<LibraryEntry> findLiveForUpdate(UUID userId, String bookKey) {
		return jdbc.sql("select " + COLUMNS + """
				 from public.library_entries
				 where user_id = :userId and book_key = :bookKey and deleted_at is null
				 for update""")
				.param("userId", userId)
				.param("bookKey", bookKey)
				.query(LibraryRepository::map)
				.optional();
	}

	/** Soft delete: a remoção vira uma escrita, para o sync levá-la aos outros aparelhos. */
	public void softDelete(UUID userId, String bookKey) {
		jdbc.sql("""
				update public.library_entries
				   set deleted_at = now(), updated_at = now(), version = version + 1
				 where user_id = :userId and book_key = :bookKey and deleted_at is null
				""")
				.param("userId", userId)
				.param("bookKey", bookKey)
				.update();
	}

	/**
	 * Uma página da estante (até `limit` linhas). `orderBy` vem de uma lista fixa no serviço; os valores
	 * do cliente entram só como parâmetros.
	 */
	public List<LibraryEntry> list(UUID userId, LibraryQuery q, List<String> statuses, String orderBy,
			LibraryCursor cursor, int limit) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from public.library_entries where user_id = :userId");
		Map<String, Object> params = new HashMap<>();
		params.put("userId", userId);
		if (q.updatedSince() != null) {
			sql.append(" and updated_at > cast(:since as timestamptz)");
			params.put("since", q.updatedSince());
		} else {
			sql.append(" and deleted_at is null");
			if (!statuses.isEmpty()) {
				sql.append(" and status in (:statuses)");
				params.put("statuses", statuses);
			}
			filter(sql, params, q.author(), " and lower(author) = lower(:author)", "author");
			filter(sql, params, q.series(), " and lower(series) = lower(:series)", "series");
			filter(sql, params, q.language(), " and language = :language", "language");
			filter(sql, params, q.genre(), " and genre = :genre and genre <> 'Da comunidade'", "genre");
			filter(sql, params, q.year(), " and extract(year from finished_on) = :year", "year");
			filter(sql, params, q.minRating(), " and rating >= :minRating", "minRating");
			filter(sql, params, q.maxRating(), " and rating <= :maxRating", "maxRating");
		}
		if (cursor != null && !cursor.isOffset()) {
			sql.append(" and (updated_at, book_key) < (cast(:cursorAt as timestamptz), :cursorKey)");
			params.put("cursorAt", cursor.updatedAt());
			params.put("cursorKey", cursor.bookKey());
		}
		sql.append(" order by ").append(orderBy).append(" limit :limit");
		params.put("limit", limit);
		if (cursor != null && cursor.isOffset()) {
			sql.append(" offset :offset");
			params.put("offset", cursor.offset());
		}
		return jdbc.sql(sql.toString()).params(params).query(LibraryRepository::map).list();
	}

	private static void filter(StringBuilder sql, Map<String, Object> params, Object value, String clause, String name) {
		if (value != null) {
			sql.append(clause);
			params.put(name, value);
		}
	}

	public LibraryStats stats(UUID userId, int year) {
		String done = "deleted_at is null and extract(year from finished_on) = :year";
		LibraryStats totals = jdbc.sql("""
				select count(*) filter (where %1$s) as finished,
				       count(*) filter (where status = 'abandoned'
				                          and extract(year from updated_at at time zone 'America/Sao_Paulo') = :year) as abandoned,
				       count(*) filter (where status = 'reading') as reading,
				       count(*) filter (where status = 'want_to_read') as want_to_read,
				       count(*) filter (where %1$s and rating is not null) as rated,
				       round(avg(rating) filter (where %1$s), 1) as average_rating
				  from public.library_entries
				 where user_id = :userId and deleted_at is null
				""".formatted(done))
				.param("userId", userId)
				.param("year", year)
				.query((rs, rowNum) -> new LibraryStats(year, rs.getInt("finished"), rs.getInt("abandoned"),
						rs.getInt("reading"), rs.getInt("want_to_read"), rs.getInt("rated"),
						rs.getBigDecimal("average_rating"), Map.of(), Map.of()))
				.single();
		Map<String, Integer> byGenre = new LinkedHashMap<>();
		jdbc.sql("select genre, count(*) as total from public.library_entries where user_id = :userId and " + done
				+ " and genre is not null and genre <> 'Da comunidade' group by genre order by total desc, genre")
				.param("userId", userId)
				.param("year", year)
				.query((rs, rowNum) -> byGenre.put(rs.getString("genre"), rs.getInt("total")))
				.list();
		Map<Integer, Integer> byMonth = new LinkedHashMap<>();
		jdbc.sql("select extract(month from finished_on)::int as month, count(*) as total from public.library_entries"
				+ " where user_id = :userId and " + done + " group by 1 order by 1")
				.param("userId", userId)
				.param("year", year)
				.query((rs, rowNum) -> byMonth.put(rs.getInt("month"), rs.getInt("total")))
				.list();
		return new LibraryStats(year, totals.finished(), totals.abandoned(), totals.reading(), totals.wantToRead(),
				totals.rated(), totals.averageRating(), byGenre, byMonth);
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
