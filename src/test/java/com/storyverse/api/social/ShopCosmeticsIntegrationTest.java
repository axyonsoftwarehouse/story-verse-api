package com.storyverse.api.social;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
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

import com.storyverse.api.common.DbRows;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.groups.GroupRepository;

/**
 * Cosméticos da loja (V56) em todas as respostas que mostram pessoas: listas da rede social, grupos,
 * ranking e perfil. As leituras rodam como a conta, no papel de runtime (a RLS vale).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class ShopCosmeticsIntegrationTest {

	static final String API = "https://api.example.test";
	static final String MEDIA = API + "/api/v1/storage/public/profile-media/";

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.auth.require-email-confirmation", () -> "false");
		properties.add("app.public-api-url", () -> API);
		properties.add("app.security.require-runtime-role", () -> "false");
	}

	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired SocialRepository social;
	@Autowired GroupRepository groups;

	/** O que a pessoa A comprou e equipou. */
	record Shop(UUID frame, UUID badge, UUID color, UUID cover, String tag) {
		String frameImage() { return "https://img.invalid/frame-" + tag + ".png"; }
		String badgeImage() { return "https://img.invalid/badge-" + tag + ".png"; }
		String coverImage() { return "https://img.invalid/cover-" + tag + ".png"; }
	}

	UUID newUser(String name) {
		UUID id = jdbc.sql("""
				insert into auth.users (email, raw_user_meta_data)
				values (:e, jsonb_build_object('name', cast(:name as text)))
				returning id
				""").param("e", UUID.randomUUID() + "@example.invalid").param("name", name).query(UUID.class).single();
		return id;
	}

	UUID newItem(String category, String name, String image, String colors) {
		return jdbc.sql("""
				insert into public.shop_items (category, tier, name, price, status, image_url, colors)
				values (:category, 'common', :name, 10, 'on_sale', :image, cast(:colors as text[]))
				returning id
				""").param("category", category).param("name", name).param("image", image).param("colors", colors)
				.query(UUID.class).single();
	}

	void own(UUID user, UUID item) {
		jdbc.sql("insert into public.shop_ownership (user_id, item_id, price_paid, ref) values (:u, :i, 10, :ref)")
				.param("u", user).param("i", item).param("ref", UUID.randomUUID().toString()).update();
	}

	void equip(UUID user, String category, UUID item) {
		jdbc.sql("insert into public.shop_equipped (user_id, category, item_id) values (:u, :c, :i)")
				.param("u", user).param("c", category).param("i", item).update();
	}

	/** A compra e equipa moldura, insígnia, cor e capa; e tem mais uma insígnia guardada (estante). */
	Shop dressUp(UUID user) {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		Shop shop = new Shop(
				newItem("frame", "Moldura " + tag, "https://img.invalid/frame-" + tag + ".png", null),
				newItem("badge", "Insígnia " + tag, "https://img.invalid/badge-" + tag + ".png", null),
				newItem("name_color", "Cor " + tag, null, "{#ff0000,#00ff00}"),
				newItem("cover", "Capa " + tag, "https://img.invalid/cover-" + tag + ".png", null),
				tag);
		UUID spare = newItem("badge", "Guardada " + tag, "https://img.invalid/spare-" + tag + ".png", null);
		for (UUID item : List.of(shop.frame(), shop.badge(), shop.color(), shop.cover(), spare)) own(user, item);
		equip(user, "frame", shop.frame());
		equip(user, "badge", shop.badge());
		equip(user, "name_color", shop.color());
		equip(user, "cover", shop.cover());
		return shop;
	}

	void follow(UUID follower, UUID followee) {
		jdbc.sql("insert into public.user_follows (follower_id, followee_id) values (:a, :b)")
				.param("a", follower).param("b", followee).update();
	}

	void setPrivate(UUID user) {
		jdbc.sql("""
				insert into public.profile_showcase (user_id, is_private) values (:u, true)
				on conflict (user_id) do update set is_private = true
				""").param("u", user).update();
	}

	/** Roda como a conta, pelo papel de runtime da VPS (a RLS vale). */
	<T> T asUser(UUID user, Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			DbSession.setUser(jdbc, user);
			return work.get();
		});
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> map(Object value) {
		assertInstanceOf(Map.class, value, "esperava um objeto JSON, veio " + value);
		return (Map<String, Object>) value;
	}

	static void assertDressed(Shop shop, Object cosmetics) {
		Map<String, Object> c = map(cosmetics);
		assertEquals(shop.frameImage(), map(c.get("frame")).get("image"));
		assertEquals("common", map(c.get("frame")).get("tier"));
		assertEquals(shop.badgeImage(), map(c.get("badge")).get("image"));
		assertEquals("Insígnia " + shop.tag(), map(c.get("badge")).get("name"));
		assertEquals(List.of("#ff0000", "#00ff00"), c.get("name_color"));
	}

	static Map<String, Object> rowOf(List<Map<String, Object>> rows, String key, UUID id) {
		return rows.stream().filter(r -> id.equals(r.get(key))).findFirst()
				.orElseThrow(() -> new AssertionError("sem linha de " + id + " em " + rows));
	}

	@Test
	void cosmeticsInFollowersAndSearch() {
		String name = "Cosmética " + UUID.randomUUID().toString().substring(0, 8);
		UUID a = newUser(name), b = newUser("Leitora B");
		Shop shop = dressUp(a);
		follow(a, b);

		List<Map<String, Object>> followers = asUser(b, () -> social.followers(b, b, 20, 0));
		assertDressed(shop, rowOf(followers, "id", a).get("cosmetics"));

		List<Map<String, Object>> found = asUser(b, () -> social.search(b, name, 10));
		assertDressed(shop, rowOf(found, "id", a).get("cosmetics"));

		List<Map<String, Object>> following = asUser(b, () -> social.following(b, a, 20, 0));
		assertNotNull(rowOf(following, "id", b).get("cosmetics"));
	}

	@Test
	void cosmeticsInGroupMessagesMembersInvitesNotifications() {
		UUID a = newUser("Dona do grupo"), b = newUser("Membro B");
		Shop shop = dressUp(a);
		UUID group = newGroup(a, "chat");
		UUID other = newGroup(a, "chat");
		jdbc.sql("insert into public.chat_members (group_id, user_id, role) values (:g, :a, 'owner'), (:g, :b, 'member'), (:o, :a, 'owner')")
				.param("g", group).param("o", other).param("a", a).param("b", b).update();
		long messageId = jdbc.sql("""
				insert into public.chat_messages (group_id, author_id, body, client_id)
				values (:g, :a, 'olá', gen_random_uuid()) returning id
				""").param("g", group).param("a", a).query(Long.class).single();
		jdbc.sql("insert into public.chat_invites (group_id, user_id, invited_by) values (:o, :b, :a)")
				.param("o", other).param("b", b).param("a", a).update();
		jdbc.sql("""
				insert into public.user_notifications (user_id, kind, group_id, message_id, actor_id)
				values (:b, 'reply', :g, :m, :a)
				""").param("b", b).param("g", group).param("m", messageId).param("a", a).update();

		List<Map<String, Object>> messages = asUser(b, () -> groups.messages(b, group, null, null, 50));
		assertDressed(shop, rowOf(messages, "author_id", a).get("author_cosmetics"));
		assertDressed(shop, asUser(b, () -> groups.message(b, messageId)).orElseThrow().get("author_cosmetics"));

		List<Map<String, Object>> members = asUser(b, () -> groups.members(group));
		assertDressed(shop, rowOf(members, "user_id", a).get("cosmetics"));

		List<Map<String, Object>> invites = asUser(b, () -> groups.myInvites(b));
		assertDressed(shop, rowOf(invites, "group_id", other).get("invited_by_cosmetics"));

		List<Map<String, Object>> invitesOfGroup = asUser(a, () -> groups.groupInvites(other));
		assertNotNull(rowOf(invitesOfGroup, "user_id", b).get("cosmetics"));

		List<Map<String, Object>> notifications = asUser(b, () -> groups.notifications(b, 20));
		assertDressed(shop, rowOf(notifications, "actor_id", a).get("actor_cosmetics"));
	}

	@Test
	void roleplayMessagesCarryNoCosmetics() {
		UUID a = newUser("Narradora"), b = newUser("Jogador B");
		dressUp(a);
		UUID group = newGroup(a, "roleplay");
		jdbc.sql("insert into public.chat_members (group_id, user_id, role) values (:g, :a, 'owner'), (:g, :b, 'member')")
				.param("g", group).param("a", a).param("b", b).update();
		jdbc.sql("""
				insert into public.chat_messages (group_id, author_id, body, client_id)
				values (:g, :a, 'fala do personagem', gen_random_uuid())
				""").param("g", group).param("a", a).update();

		List<Map<String, Object>> messages = asUser(b, () -> groups.messages(b, group, null, null, 50));
		Map<String, Object> row = rowOf(messages, "author_id", a);
		assertTrue(row.containsKey("author_cosmetics"));
		assertNull(row.get("author_cosmetics"), "personagem não leva moldura nem nome colorido");
		// Na lista de membros do grupo de roleplay a pessoa aparece com o equipado.
		assertNotNull(map(rowOf(asUser(b, () -> groups.members(group)), "user_id", a).get("cosmetics")).get("frame"));
	}

	UUID newGroup(UUID owner, String mode) {
		return jdbc.sql("""
				insert into public.chat_groups (name, visibility, owner_id, invite_code, mode)
				values (:name, 'private', :owner, :code, :mode) returning id
				""").param("name", "Clube " + UUID.randomUUID().toString().substring(0, 6)).param("owner", owner)
				.param("code", UUID.randomUUID().toString().replace("-", "")).param("mode", mode)
				.query(UUID.class).single();
	}

	@Test
	void cosmeticsInRanking() {
		UUID a = newUser("Leitora do ranking");
		Shop shop = dressUp(a);
		jdbc.sql("insert into public.ranking_profiles (user_id, name) values (:a, 'Leitora do ranking')").param("a", a).update();
		jdbc.sql("insert into public.reading_days (user_id, day, seconds) values (:a, public.ranking_week_start(0), 600)")
				.param("a", a).update();

		List<Map<String, Object>> weekly = asUser(a, () -> DbRows.list(jdbc,
				"select * from public.weekly_ranking(p_offset => 0, p_limit => 50)", Map.of()));
		assertDressed(shop, rowOf(weekly, "user_id", a).get("cosmetics"));

		List<Map<String, Object>> alltime = asUser(a, () -> DbRows.list(jdbc,
				"select * from public.alltime_ranking(p_limit => 50)", Map.of()));
		assertDressed(shop, rowOf(alltime, "user_id", a).get("cosmetics"));
	}

	@Test
	void privateProfileHidesCoverAndShelf() {
		UUID a = newUser("Perfil privado"), b = newUser("Visitante B");
		Shop shop = dressUp(a);
		String uploaded = MEDIA + a + "/cover.webp";
		jdbc.sql("""
				update auth.users set raw_user_meta_data = raw_user_meta_data || jsonb_build_object('cover', cast(:cover as text))
				 where id = :a
				""").param("cover", uploaded).param("a", a).update();
		setPrivate(a);

		Map<String, Object> hidden = asUser(b, () -> social.profile(b, a)).orElseThrow();
		assertDressed(shop, hidden.get("cosmetics"));
		assertTrue(hidden.containsKey("shop_cover"));
		assertNull(hidden.get("shop_cover"));
		assertTrue(hidden.containsKey("cover"));
		assertNull(hidden.get("cover"));
		assertEquals(List.of(), hidden.get("badge_shelf"));

		follow(b, a);
		Map<String, Object> open = asUser(b, () -> social.profile(b, a)).orElseThrow();
		assertDressed(shop, open.get("cosmetics"));
		assertEquals(shop.coverImage(), open.get("shop_cover"));
		assertEquals(uploaded, open.get("cover"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> shelf = (List<Map<String, Object>>) open.get("badge_shelf");
		assertEquals(2, shelf.size(), "a estante mostra todas as insígnias que a pessoa tem");
		assertTrue(shelf.stream().anyMatch(s -> shop.badge().toString().equals(s.get("id"))
				&& shop.badgeImage().equals(s.get("image")) && ("Insígnia " + shop.tag()).equals(s.get("name"))));
	}

	@Test
	void coverFromAnotherFolderIsDropped() {
		UUID a = newUser("Capa de fora"), b = newUser("Visitante");
		for (String bad : Arrays.asList("https://tracker.invalid/x.png", MEDIA + b + "/cover.webp")) {
			jdbc.sql("""
					update auth.users set raw_user_meta_data = raw_user_meta_data || jsonb_build_object('cover', cast(:cover as text))
					 where id = :a
					""").param("cover", bad).param("a", a).update();
			Map<String, Object> profile = asUser(b, () -> social.profile(b, a)).orElseThrow();
			assertNull(profile.get("cover"), bad);
		}
	}

	@Test
	void unequippedIsNull() {
		UUID a = newUser("Sem itens"), b = newUser("Quem olha");
		Map<String, Object> profile = asUser(b, () -> social.profile(b, a)).orElseThrow();
		Map<String, Object> cosmetics = map(profile.get("cosmetics"));
		assertEquals(3, cosmetics.size());
		assertTrue(cosmetics.containsKey("frame") && cosmetics.get("frame") == null);
		assertTrue(cosmetics.containsKey("badge") && cosmetics.get("badge") == null);
		assertTrue(cosmetics.containsKey("name_color") && cosmetics.get("name_color") == null);
		assertNull(profile.get("shop_cover"));
		assertEquals(List.of(), profile.get("badge_shelf"));
	}
}
