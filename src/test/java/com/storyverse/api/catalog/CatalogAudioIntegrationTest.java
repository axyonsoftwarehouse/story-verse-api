package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.storyverse.api.auth.AuthResponse;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.SignupRequest;
import com.storyverse.api.common.DbSession;

/** Fluxo completo do cadastro de audio contra um PostgreSQL descartavel. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class CatalogAudioIntegrationTest {

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.auth.require-email-confirmation", () -> "false");
		properties.add("app.security.require-runtime-role", () -> "false");
		properties.add("app.r2.endpoint", () -> System.getenv().getOrDefault("R2_ENDPOINT", ""));
		properties.add("app.r2.access-key", () -> System.getenv().getOrDefault("R2_ACCESS_KEY_ID", ""));
		properties.add("app.r2.secret-key", () -> System.getenv().getOrDefault("R2_SECRET_ACCESS_KEY", ""));
		properties.add("app.r2.bucket", () -> System.getenv().getOrDefault("R2_BUCKET", "catalog-audio"));
		properties.add("app.r2.region", () -> System.getenv().getOrDefault("R2_REGION", "auto"));
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	AuthService auth;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager transactions;

	private <T> T asRuntime(UUID uid, String role, Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role " + role).update();
			if (uid != null) DbSession.setUser(jdbc, uid);
			return work.get();
		});
	}

	private int chapterCount(UUID audioId) {
		return jdbc.sql("select count(*) from public.catalog_audio_chapters where audio_id = :audio")
				.param("audio", audioId)
				.query(Integer.class)
				.single();
	}

	private AuthResponse userWithRole(String role) {
		AuthResponse session = (AuthResponse) auth.signup(
				new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Audio"));
		jdbc.sql("insert into public.user_roles(user_id, role) values(:id, :role)")
				.param("id", UUID.fromString(session.user().id().toString()))
				.param("role", role)
				.update();
		return session;
	}

	private UUID book() {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into public.catalog_books
					(id, book_key, source, title, language, rights, storage_bucket, text_path,
					 status, published_at)
				values
					(:id, :key, 'admin', 'Audio Integration', 'pt', 'public_domain',
					 'catalog-books', :path, 'published', now())
				""")
				.param("id", id)
				.param("key", "it:audio:" + id)
				.param("path", "it/" + id + ".txt")
				.update();
		return id;
	}

	private static String bearer(AuthResponse session) {
		return "Bearer " + session.accessToken();
	}

	private int audits(String action, UUID target) {
		return jdbc.sql("select count(*) from public.admin_audit_log where action = :action and target_id = :target")
				.param("action", action)
				.param("target", target.toString())
				.query(Integer.class)
				.single();
	}

	private int chapterAudits(String action, UUID audioId) {
		return jdbc.sql("""
				select count(*) from public.admin_audit_log
				 where action = :action and metadata ->> 'audioId' = :audio
				""")
				.param("action", action)
				.param("audio", audioId.toString())
				.query(Integer.class)
				.single();
	}

	@Test
	void moderatorCadastraMapeiaPublicaELista() throws Exception {
		AuthResponse moderator = userWithRole("moderator");
		UUID bookId = book();
		String bookKey = "it:audio:" + bookId;

		MvcResult created = mvc.perform(post("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"language":"pt","narrator":"Karen Savage","source":"librivox",
								 "rights":"public_domain","licenseNote":"LibriVox"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("draft"))
				.andExpect(jsonPath("$.chapter_count").value(0))
				.andReturn();
		UUID audioId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
		String path = "books/" + bookKey + "/" + audioId + "/capitulo.mp3";

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/chapters", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"path":"%s","chapterIndex":1,"title":"Capitulo I",
								 "durationSeconds":60,"startSeconds":0,"endSeconds":60}
								""".formatted(path)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.chapter_index").value(1))
				.andExpect(jsonPath("$.text_chapter_index").doesNotExist());

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/chapters", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"path":"%s","chapterIndex":2,"title":"Capitulo II",
								 "durationSeconds":120,"startSeconds":60,"endSeconds":180}
								""".formatted(path)))
				.andExpect(status().isCreated());

		mvc.perform(patch("/api/v1/admin/catalog/audio/{audioId}/chapters/{index}", audioId, 1)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"textChapterIndex\":1,\"textChapterEndIndex\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.text_chapter_index").value(1));

		mvc.perform(patch("/api/v1/admin/catalog/audio/{audioId}/chapters/{index}", audioId, 2)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"textChapterIndex\":2,\"textChapterEndIndex\":2}"))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions[0].chapter_count").value(2))
				.andExpect(jsonPath("$.editions[0].total_duration_seconds").value(180))
				.andExpect(jsonPath("$.editions[0].chapters.length()").value(2))
				.andExpect(jsonPath("$.editions[0].chapters[1].text_chapter_index").value(2))
				.andExpect(jsonPath("$.editions[0].text_mapping").value("pending"));

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/publish", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error")
						.value("Defina o mapeamento do texto (mapeado ou sem texto) antes de publicar."));

		mvc.perform(patch("/api/v1/admin/catalog/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"textMapping\":\"mapped\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.text_mapping").value("mapped"));

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/publish", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("published"));

		mvc.perform(get("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions[0].status").value("published"));

		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio", bookKey))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.book.bookKey").value(bookKey))
				.andExpect(jsonPath("$.editions.length()").value(1))
				.andExpect(jsonPath("$.editions[0].chapters.length()").value(2))
				.andExpect(jsonPath("$.editions[0].chapters[0].free").value(true))
				.andExpect(jsonPath("$.editions[0].chapters[1].free").value(false))
				.andExpect(jsonPath("$.editions[0].chapters[0].audio_path").doesNotExist());

		mvc.perform(get("/api/v1/catalog/audio"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.audiobooks[?(@.bookKey == '%s')]".formatted(bookKey)).isNotEmpty());

		MvcResult firstStream = mvc.perform(get(
						"/api/v1/catalog/books/{bookKey}/audio/{audioId}/chapters/1/stream", bookKey, audioId))
				.andReturn();
		int firstStatus = firstStream.getResponse().getStatus();
		assertTrue(firstStatus == 200 || firstStatus == 503, "stream capítulo 1: " + firstStatus);
		if (firstStatus == 200) {
			String body = firstStream.getResponse().getContentAsString();
			assertTrue(body.contains("\"url\""));
			assertTrue(!body.contains("audio_path"));
		}

		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio/{audioId}/chapters/2/stream", bookKey, audioId))
				.andExpect(status().isUnauthorized());

		MvcResult secondStream = mvc.perform(get(
						"/api/v1/catalog/books/{bookKey}/audio/{audioId}/chapters/2/stream", bookKey, audioId)
						.header("Authorization", bearer(moderator)))
				.andReturn();
		int secondStatus = secondStream.getResponse().getStatus();
		assertTrue(secondStatus == 200 || secondStatus == 503, "stream capítulo 2: " + secondStatus);

		mvc.perform(put("/api/v1/me/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"chapterIndex\":1,\"positionSeconds\":10,\"playbackRate\":1.25}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chapterIndex").value(1))
				.andExpect(jsonPath("$.positionSeconds").value(10));

		mvc.perform(get("/api/v1/me/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.positionSeconds").value(10));

		mvc.perform(put("/api/v1/me/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"chapterIndex\":1,\"positionSeconds\":9999,\"playbackRate\":1.25}"))
				.andExpect(status().isBadRequest());

		assertTrue(audits("catalog.audio.create", audioId) >= 1);
		assertTrue(chapterAudits("catalog.audio.chapter.create", audioId) >= 2);
		assertTrue(chapterAudits("catalog.audio.chapter.update", audioId) >= 2);
		assertTrue(audits("catalog.audio.publish", audioId) >= 1);
	}

	@Test
	void uploadUrlDependeDoR2EModeradorNaoApaga() throws Exception {
		AuthResponse moderator = userWithRole("moderator");
		UUID bookId = book();

		MvcResult created = mvc.perform(post("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"language\":\"pt\",\"source\":\"librivox\",\"rights\":\"public_domain\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID audioId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));

		MvcResult upload = mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/upload-url", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"fileName":"capitulo.mp3","contentType":"audio/mpeg","sizeBytes":1024}
								"""))
				.andReturn();
		String configuredKey = System.getenv("R2_ACCESS_KEY_ID");
		boolean r2Configured = configuredKey != null && !configuredKey.isBlank();
		assertEquals(r2Configured ? 200 : 503, upload.getResponse().getStatus());

		mvc.perform(delete("/api/v1/admin/catalog/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isForbidden());

		AuthResponse superadmin = userWithRole("superadmin");
		mvc.perform(delete("/api/v1/admin/catalog/audio/{audioId}", audioId)
						.header("Authorization", bearer(superadmin)))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions.length()").value(0));

		assertTrue(audits("catalog.audio.delete", audioId) >= 1);
		assertEquals(0, jdbc.sql("select count(*) from public.catalog_book_audio where id = :id")
				.param("id", audioId)
				.query(Integer.class)
				.single());
	}

	@Test
	void publishExigeJustificativaDeDireitosQuandoNaoEDominioPublico() throws Exception {
		AuthResponse moderator = userWithRole("moderator");
		UUID bookId = book();

		MvcResult created = mvc.perform(post("/api/v1/admin/catalog/books/{bookId}/audio", bookId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"language":"pt","source":"admin","rights":"own_work",
								 "textMapping":"none"}
								"""))
				.andExpect(status().isCreated())
				.andReturn();
		UUID audioId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
		String path = "books/it:audio:" + bookId + "/" + audioId + "/capitulo.mp3";

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/chapters", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"path":"%s","chapterIndex":1,"title":"Capitulo I",
								 "durationSeconds":60,"startSeconds":0,"endSeconds":60}
								""".formatted(path)))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/publish", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Explique por que este áudio pode ser publicado."));

		mvc.perform(patch("/api/v1/admin/catalog/audio/{audioId}", audioId)
						.header("Authorization", bearer(moderator))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"rightsNote\":\"Obra propria do autor\"}"))
				.andExpect(status().isOk());

		mvc.perform(post("/api/v1/admin/catalog/audio/{audioId}/publish", audioId)
						.header("Authorization", bearer(moderator)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("published"));
	}

	@Test
	void capitulosComAudioPathNaoSaoLegiveisPorAnonOuAuthenticated() {
		AuthResponse moderator = userWithRole("moderator");
		UUID bookId = book();
		UUID audioId = UUID.randomUUID();

		jdbc.sql("insert into public.catalog_book_audio (id, book_id) values (:id, :book)")
				.param("id", audioId)
				.param("book", bookId)
				.update();
		jdbc.sql("""
				insert into public.catalog_audio_chapters
					(audio_id, chapter_index, audio_path, duration_seconds)
				values (:audio, 1, 'books/x/1.mp3', 60)
				""")
				.param("audio", audioId)
				.update();

		UUID moderatorId = UUID.fromString(moderator.user().id().toString());
		assertEquals(1, asRuntime(moderatorId, "storyverse_api", () -> chapterCount(audioId)));
		assertThrows(DataAccessException.class,
				() -> asRuntime(null, "authenticated", () -> chapterCount(audioId)));
		assertThrows(DataAccessException.class,
				() -> asRuntime(null, "anon", () -> chapterCount(audioId)));
	}

	/** Edição publicada direto no banco, com os capítulos e durações dados (S4). */
	private UUID publishedEdition(UUID bookId, int... durations) {
		UUID audioId = UUID.randomUUID();
		int total = java.util.Arrays.stream(durations).sum();
		jdbc.sql("""
				insert into public.catalog_book_audio
					(id, book_id, status, published_at, chapter_count, total_duration_seconds, text_mapping)
				values (:id, :book, 'published', now(), :count, :total, 'none')
				""")
				.param("id", audioId)
				.param("book", bookId)
				.param("count", durations.length)
				.param("total", total)
				.update();
		for (int i = 0; i < durations.length; i++) {
			jdbc.sql("""
					insert into public.catalog_audio_chapters
						(audio_id, chapter_index, audio_path, duration_seconds)
					values (:audio, :index, :path, :duration)
					""")
					.param("audio", audioId)
					.param("index", i + 1)
					.param("path", "books/s4/" + audioId + "/" + (i + 1) + ".mp3")
					.param("duration", durations[i])
					.update();
		}
		return audioId;
	}

	private String bookKey(UUID bookId) {
		return "it:audio:" + bookId;
	}

	/** S4: com um capítulo só, o capítulo 1 é o livro inteiro e não pode sair sem login. */
	@Test
	void edicaoDeCapituloUnicoNaoTemAmostraAberta() throws Exception {
		UUID bookId = book();
		UUID audioId = publishedEdition(bookId, 25_916);

		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio", bookKey(bookId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions[0].chapters[0].free").value(false))
				.andExpect(jsonPath("$.editions[0].freeChapterIndex").value(org.hamcrest.Matchers.nullValue()));
		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio/{audioId}/chapters/1/stream",
						bookKey(bookId), audioId))
				.andExpect(status().isUnauthorized());
	}

	/** S4: primeiro capítulo longo demais também não é amostra. */
	@Test
	void primeiroCapituloLongoNaoEAmostraAberta() throws Exception {
		UUID bookId = book();
		UUID audioId = publishedEdition(bookId, 3_600, 600);

		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio", bookKey(bookId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions[0].chapters[0].free").value(false))
				.andExpect(jsonPath("$.editions[0].chapters[1].free").value(false));
		mvc.perform(get("/api/v1/catalog/books/{bookKey}/audio/{audioId}/chapters/1/stream",
						bookKey(bookId), audioId))
				.andExpect(status().isUnauthorized());
	}
}
