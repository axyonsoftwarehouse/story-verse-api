package com.storyverse.api.social;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.account.AccountService;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthResponse;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.ProfileUpdateRequest;
import com.storyverse.api.auth.SignupRequest;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.groups.GroupService;

/** Rede social (V46): seguir, conexões, listas, busca, sugestões, sininho e RLS. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class SocialIntegrationTest {
	static final Path media;
	static {
		try {
			media = Files.createTempDirectory("storyverse-social-it-");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.storage.dir", () -> media.toString());
		properties.add("app.auth.require-email-confirmation", () -> "false");
		properties.add("app.public-api-url", () -> "https://api.example.test");
		properties.add("app.rate-limit.persistent", () -> "true");
		properties.add("app.security.require-runtime-role", () -> "false");
	}

	@Autowired AuthService auth;
	@Autowired SocialService social;
	@Autowired GroupService groups;
	@Autowired AccountService account;
	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;

	<T> T asRuntime(Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			return action.get();
		});
	}

	/** Conta com a feature da comunidade (grupos), com nome próprio para a busca. */
	String person(String name) {
		AuthResponse session = (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", name));
		UUID id = UUID.fromString(session.user().id().toString());
		jdbc.sql("insert into public.feature_access(user_id, feature) values (:id, 'groups')").param("id", id).update();
		jdbc.sql("update auth.users set created_at = now() - interval '2 days' where id = :id").param("id", id).update();
		return session.accessToken();
	}

	UUID idOf(String token) {
		return auth.authenticate(token);
	}

	static long number(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).longValue();
	}

	@Test
	void withoutFeatureIsBlocked() {
		AuthResponse session = (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Sem"));
		AuthException denied = assertThrows(AuthException.class, () -> social.suggestions(session.accessToken()));
		assertEquals("FEATURE_DISABLED", denied.code());
	}

	@Test
	void followBecomesConnectionWhenMutual() {
		String ana = person("Ana Social"), bia = person("Bia Social");
		UUID anaId = idOf(ana), biaId = idOf(bia);

		Map<String, Object> afterFollow = social.follow(ana, biaId);
		assertEquals(Boolean.TRUE, afterFollow.get("is_following"));
		assertEquals(Boolean.FALSE, afterFollow.get("follows_me"));
		assertEquals(1L, number(afterFollow, "followers"));
		assertEquals(0L, number(afterFollow, "connections"));

		// Seguir de novo não duplica.
		assertEquals(1L, number(social.follow(ana, biaId), "followers"));

		Map<String, Object> seenByBia = social.profile(bia, anaId);
		assertEquals(Boolean.TRUE, seenByBia.get("follows_me"));
		assertEquals(Boolean.FALSE, seenByBia.get("is_me"));

		social.follow(bia, anaId);
		Map<String, Object> mutual = social.profile(ana, biaId);
		assertEquals(1L, number(mutual, "connections"));
		assertEquals(Boolean.TRUE, mutual.get("follows_me"));

		assertEquals(Boolean.TRUE, social.profile(ana, anaId).get("is_me"));

		List<Map<String, Object>> followers = social.followers(ana, biaId, null, null);
		assertEquals(1, followers.size());
		assertEquals(anaId, followers.get(0).get("id"));
		assertEquals("Ana Social", followers.get(0).get("name"));
		assertEquals(List.of(biaId), social.following(bia, anaId, null, null).stream().map(r -> r.get("id")).toList());

		Map<String, Object> afterUnfollow = social.unfollow(ana, biaId);
		assertEquals(Boolean.FALSE, afterUnfollow.get("is_following"));
		assertEquals(0L, number(afterUnfollow, "followers"));
		assertEquals(0L, number(afterUnfollow, "connections"));
	}

	@Test
	void profileShowsTheBio() {
		String ana = person("Ana Bio"), bia = person("Bia Bio");
		UUID anaId = idOf(ana);
		assertNull(social.profile(bia, anaId).get("bio"));
		auth.updateProfile(ana, new ProfileUpdateRequest("Ana Bio", null, null, null, "Leio de tudo um pouco"));
		assertEquals("Leio de tudo um pouco", social.profile(bia, anaId).get("bio"));
	}

	@Test
	void cannotFollowSelfOrUnknown() {
		String ana = person("Ana Sozinha");
		assertEquals(400, assertThrows(AuthException.class, () -> social.follow(ana, idOf(ana))).status());
		assertEquals(404, assertThrows(AuthException.class, () -> social.follow(ana, UUID.randomUUID())).status());
		assertEquals(404, assertThrows(AuthException.class, () -> social.profile(ana, UUID.randomUUID())).status());
	}

	@Test
	void removeFollowerTakesThemOut() {
		String ana = person("Ana Remove"), bia = person("Bia Remove");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		social.follow(bia, anaId);
		assertEquals(1L, number(social.profile(ana, anaId), "followers"));
		social.removeFollower(ana, biaId);
		assertEquals(0L, number(social.profile(ana, anaId), "followers"));
		assertEquals(Boolean.FALSE, social.profile(bia, anaId).get("is_following"));
	}

	@Test
	void followNotifiesOnceADay() {
		String ana = person("Ana Avisa"), bia = person("Bia Avisada");
		UUID biaId = idOf(bia);
		social.follow(ana, biaId);
		social.unfollow(ana, biaId);
		social.follow(ana, biaId);

		Map<String, Object> bell = groups.notifications(bia);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) bell.get("items");
		List<Map<String, Object>> follows = items.stream().filter(n -> "follow".equals(n.get("kind"))).toList();
		assertEquals(1, follows.size(), "seguir/desseguir em sequência não repete o aviso");
		assertNull(follows.get(0).get("group_id"));
		assertEquals("Ana Avisa", follows.get(0).get("actor_name"));
		assertEquals(1, ((Number) bell.get("unread")).intValue());
	}

	@Test
	void searchFindsByNameNotEmail() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String ana = person("Ana " + tag);
		String bia = person("Bia " + tag);
		List<Map<String, Object>> found = social.search(ana, tag);
		assertEquals(List.of(idOf(bia)), found.stream().map(r -> r.get("id")).toList(), "acha pelo nome e não traz a própria conta");
		assertEquals(List.of(), social.search(ana, "a"), "busca curta demais não lista ninguém");
		assertEquals(List.of(), social.search(ana, "example.invalid"), "e-mail não é buscável");
		assertEquals(List.of(), social.search(ana, "%"), "curinga do LIKE é texto comum");
	}

	@Test
	void suggestionsComeFromSharedGroupsAndFollowers() {
		String ana = person("Ana Sugere"), bia = person("Bia Grupo"), caio = person("Caio Segue");
		UUID biaId = idOf(bia), caioId = idOf(caio);
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Clube " + UUID.randomUUID().toString().substring(0, 6));
		body.put("visibility", "public");
		UUID groupId = UUID.fromString(String.valueOf(groups.create(ana, body).get("id")));
		groups.join(bia, groupId);
		social.follow(caio, idOf(ana));

		List<Map<String, Object>> suggestions = social.suggestions(ana);
		List<Object> ids = suggestions.stream().map(r -> r.get("id")).toList();
		assertEquals(List.of(caioId, biaId), ids, "quem já segue vem primeiro (seguir de volta), depois quem divide grupo");
		assertEquals(1L, number(suggestions.get(1), "groups_in_common"));
		assertEquals(1L, number(social.profile(ana, biaId), "groups_in_common"));

		social.follow(ana, biaId);
		assertFalse(social.suggestions(ana).stream().anyMatch(r -> biaId.equals(r.get("id"))), "quem já segue sai das sugestões");
	}

	@Test
	void rlsOnlyLetsEachOneFollowAsThemselves() {
		String ana = person("Ana RLS"), bia = person("Bia RLS"), caio = person("Caio RLS");
		UUID anaId = idOf(ana), biaId = idOf(bia), caioId = idOf(caio);
		social.follow(bia, caioId);

		// Ana não cria vínculo em nome da Bia.
		assertThrows(RuntimeException.class, () -> asRuntime(() -> {
			DbSession.setUser(jdbc, anaId);
			return jdbc.sql("insert into public.user_follows (follower_id, followee_id) values (:f, :t)")
					.param("f", biaId).param("t", anaId).update();
		}));
		// Nem desfaz o vínculo dos outros.
		int removed = asRuntime(() -> {
			DbSession.setUser(jdbc, anaId);
			return jdbc.sql("delete from public.user_follows where follower_id = :f and followee_id = :t")
					.param("f", biaId).param("t", caioId).update();
		});
		assertEquals(0, removed);
		// Quem é seguido tira o seguidor.
		int removedByCaio = asRuntime(() -> {
			DbSession.setUser(jdbc, caioId);
			return jdbc.sql("delete from public.user_follows where follower_id = :f and followee_id = :t")
					.param("f", biaId).param("t", caioId).update();
		});
		assertEquals(1, removedByCaio);
	}

	@Test
	void exportIncludesFollows() {
		String ana = person("Ana Export"), bia = person("Bia Export");
		social.follow(ana, idOf(bia));
		social.follow(bia, idOf(ana));
		Map<String, Object> data = account.export(ana);
		assertEquals(1, ((List<?>) data.get("following")).size());
		assertEquals(1, ((List<?>) data.get("followers")).size());
	}
	void shelf(UUID user, String key, String source, String status, String title) {
		jdbc.sql("insert into public.library_entries (user_id, book_key, source, status, title, author, finished_on) values (:u, :k, :s, :st, :t, 'Autor', case when :st = 'finished' then current_date end)")
				.param("u", user).param("k", key).param("s", source).param("st", status).param("t", title).update();
	}

	@Test
	@SuppressWarnings("unchecked")
	void profileShowsReadingBadgesAndGiftsUnlessHidden() {
		String ana = person("Ana Vitrine"), bia = person("Bia Visita");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		shelf(anaId, "gb:1342", "gutenberg", "finished", "Orgulho e Preconceito");
		shelf(anaId, "gb:84", "gutenberg", "reading", "Frankenstein");
		shelf(anaId, "gb:11", "gutenberg", "want_to_read", "Alice");
		shelf(anaId, "local:-5", "local", "finished", "Arquivo particular");
		jdbc.sql("insert into public.user_gifts (from_user, to_user, gift_id, week_start, ref) values (:f, :t, 'flor', current_date, :r)")
				.param("f", biaId).param("t", anaId).param("r", UUID.randomUUID().toString()).update();

		Map<String, Object> body = new HashMap<>();
		body.put("badges", List.of(Map.of("id", "first-page", "emoji", "📖", "title", "Primeira página"), Map.of("id", "x"), "lixo"));
		body.put("total", 17);
		body.put("stats", Map.of("totalMinutes", 125, "bestStreak", 4, "hack", 9));
		social.saveShowcase(ana, body);

		Map<String, Object> seen = social.profile(bia, anaId);
		assertEquals(Boolean.FALSE, seen.get("reading_hidden"));
		List<Map<String, Object>> books = (List<Map<String, Object>>) seen.get("books");
		assertEquals(List.of("Frankenstein", "Orgulho e Preconceito"), books.stream().map(b -> b.get("title")).toList(),
				"lendo primeiro; sem 'quero ler' nem livro importado do aparelho");
		Map<String, Object> counts = (Map<String, Object>) seen.get("reading_counts");
		assertEquals(1, ((Number) counts.get("finished")).intValue());
		List<Map<String, Object>> badges = (List<Map<String, Object>>) seen.get("badges");
		assertEquals(1, badges.size(), "item fora do formato é descartado");
		assertEquals("Primeira página", badges.get(0).get("title"));
		assertEquals(17, ((Number) seen.get("badges_total")).intValue());
		Map<String, Object> stats = (Map<String, Object>) seen.get("stats");
		assertEquals(125, ((Number) stats.get("totalMinutes")).intValue());
		assertFalse(stats.containsKey("hack"));
		List<Map<String, Object>> gifts = (List<Map<String, Object>>) seen.get("gifts");
		assertEquals("flor", gifts.get(0).get("gift_id"));
		assertEquals(1, ((Number) gifts.get(0).get("total")).intValue());
		assertEquals(2, social.books(bia, anaId, null, null).size());

		social.savePrivacy(ana, Map.of("showReading", false));
		Map<String, Object> hidden = social.profile(bia, anaId);
		assertEquals(Boolean.TRUE, hidden.get("reading_hidden"));
		assertEquals(List.of(), hidden.get("books"));
		assertEquals(List.of(), hidden.get("badges"));
		assertEquals(List.of(), hidden.get("gifts"));
		assertEquals(List.of(), social.books(bia, anaId, null, null));
		// A própria pessoa continua vendo tudo (e sabe que está escondido).
		Map<String, Object> own = social.profile(ana, anaId);
		assertEquals(Boolean.FALSE, own.get("reading_hidden"));
		assertEquals(Boolean.FALSE, own.get("show_reading"));
		assertEquals(2, ((List<?>) own.get("books")).size());
	}

	@Test
	void runtimeCannotReadOthersShelfDirectlyOrWriteOthersShowcase() {
		String ana = person("Ana Privada"), bia = person("Bia Curiosa");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		shelf(anaId, "gb:2701", "gutenberg", "finished", "Moby Dick");
		int direct = asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("select count(*) from public.library_entries where user_id = :u").param("u", anaId).query(Integer.class).single();
		});
		assertEquals(0, direct, "a estante continua só do dono; o perfil passa pela função");
		assertThrows(RuntimeException.class, () -> asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("insert into public.profile_showcase (user_id, show_reading) values (:u, false)").param("u", anaId).update();
		}));
	}

	@Test
	void clearNotificationsEmptiesTheBell() {
		String ana = person("Ana Limpa"), bia = person("Bia Segue");
		social.follow(bia, idOf(ana));
		assertEquals(1, ((Number) groups.notifications(ana).get("unread")).intValue());
		groups.clearNotifications(ana);
		Map<String, Object> bell = groups.notifications(ana);
		assertEquals(List.of(), bell.get("items"));
		assertEquals(0, ((Number) bell.get("unread")).intValue());
	}

	@SuppressWarnings("unchecked")
	List<Map<String, Object>> bellOf(String token, String kind) {
		List<Map<String, Object>> items = (List<Map<String, Object>>) groups.notifications(token).get("items");
		return items.stream().filter(n -> kind.equals(n.get("kind"))).toList();
	}

	@Test
	void privateProfileTurnsFollowIntoRequest() {
		String ana = person("Ana Privada"), bia = person("Bia Pede");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		social.follow(person("Caio Antigo"), anaId);

		Map<String, Object> saved = social.savePrivacy(ana, Map.of("private", true));
		assertEquals(Boolean.TRUE, saved.get("is_private"));
		assertEquals(Boolean.TRUE, saved.get("show_reading"), "mudar só o privado não mexe nas leituras");

		Map<String, Object> asked = social.follow(bia, anaId);
		assertEquals(Boolean.FALSE, asked.get("is_following"));
		assertEquals(Boolean.TRUE, asked.get("requested"));
		assertEquals(1L, number(asked, "followers"), "o pedido não conta como seguidor");
		Map<String, Object> found = social.search(bia, "Ana Privada").get(0);
		assertEquals(Boolean.TRUE, found.get("is_private"), "as listas já dizem que o perfil é privado");
		assertEquals(Boolean.TRUE, found.get("requested"));
		// Pedir de novo não duplica nem repete o aviso.
		social.follow(bia, anaId);

		Map<String, Object> seen = social.profile(bia, anaId);
		assertEquals(Boolean.TRUE, seen.get("is_private"));
		assertEquals(Boolean.FALSE, seen.get("content_visible"));
		assertEquals(Boolean.TRUE, seen.get("reading_hidden"));
		assertEquals(List.of(), social.followers(bia, anaId, null, null), "lista de seguidores fechada para quem não segue");
		assertEquals(List.of(), social.following(bia, anaId, null, null));

		assertEquals(1L, number(social.profile(ana, anaId), "pending_requests"));
		assertEquals(List.of(biaId), social.requests(ana, null, null).stream().map(r -> r.get("id")).toList());
		assertEquals(1, bellOf(ana, "follow_request").size());
		assertEquals(0, bellOf(ana, "follow").size());

		social.acceptRequest(ana, biaId);
		Map<String, Object> after = social.profile(bia, anaId);
		assertEquals(Boolean.TRUE, after.get("is_following"));
		assertEquals(Boolean.FALSE, after.get("requested"));
		assertEquals(Boolean.TRUE, after.get("content_visible"));
		assertEquals(2, social.followers(bia, anaId, null, null).size());
		assertEquals(0, bellOf(ana, "follow_request").size(), "o aviso do pedido sai do sininho");
		assertEquals(1, bellOf(bia, "follow_accepted").size());
		assertEquals(404, assertThrows(AuthException.class, () -> social.acceptRequest(ana, biaId)).status());
	}

	@Test
	void requestCanBeDeclinedOrCancelled() {
		String ana = person("Ana Recusa"), bia = person("Bia Recusada");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		social.savePrivacy(ana, Map.of("private", true));

		social.follow(bia, anaId);
		social.declineRequest(ana, biaId);
		assertEquals(List.of(), social.requests(ana, null, null));
		assertEquals(Boolean.FALSE, social.profile(bia, anaId).get("requested"));
		assertEquals(0, bellOf(bia, "follow_accepted").size(), "recusa não avisa");

		social.follow(bia, anaId);
		social.unfollow(bia, anaId);
		assertEquals(List.of(), social.requests(ana, null, null), "deixar de seguir cancela o pedido");
		assertEquals(0, bellOf(ana, "follow_request").size());
	}

	@Test
	void goingPublicAcceptsPendingRequests() {
		String ana = person("Ana Abre"), bia = person("Bia Espera");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		social.savePrivacy(ana, Map.of("private", true));
		social.follow(bia, anaId);

		Map<String, Object> saved = social.savePrivacy(ana, Map.of("private", false));
		assertEquals(Boolean.FALSE, saved.get("is_private"));
		assertEquals(1, ((Number) saved.get("accepted_requests")).intValue());
		assertEquals(Boolean.TRUE, social.profile(bia, anaId).get("is_following"));
		assertEquals(List.of(), social.requests(ana, null, null));

		// Público de novo: seguir é direto.
		String caio = person("Caio Direto");
		assertEquals(Boolean.TRUE, social.follow(caio, anaId).get("is_following"));
		assertEquals(400, assertThrows(AuthException.class, () -> social.savePrivacy(ana, Map.of())).status());
		assertEquals(400, assertThrows(AuthException.class, () -> social.savePrivacy(ana, Map.of("private", "sim"))).status());
	}

	@Test
	void privateProfileCannotBeFollowedDirectlyInTheDatabase() {
		String ana = person("Ana Tranca"), bia = person("Bia Fura");
		UUID anaId = idOf(ana), biaId = idOf(bia);
		social.savePrivacy(ana, Map.of("private", true));

		assertThrows(RuntimeException.class, () -> asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("insert into public.user_follows (follower_id, followee_id) values (:f, :t)")
					.param("f", biaId).param("t", anaId).update();
		}));
		// Nem cria pedido em nome de outra pessoa.
		assertThrows(RuntimeException.class, () -> asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("insert into public.follow_requests (requester_id, target_id) values (:r, :t)")
					.param("r", anaId).param("t", biaId).update();
		}));
		// Pedido próprio pode; aceitar é só da dona do perfil.
		int created = asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("insert into public.follow_requests (requester_id, target_id) values (:r, :t)")
					.param("r", biaId).param("t", anaId).update();
		});
		assertEquals(1, created);
		boolean acceptedByBia = asRuntime(() -> {
			DbSession.setUser(jdbc, biaId);
			return jdbc.sql("select public.social_accept_follow_request(:r)").param("r", biaId).query(Boolean.class).single();
		});
		assertFalse(acceptedByBia);
	}
}
