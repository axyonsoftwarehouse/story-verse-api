package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthResponse;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.SignupRequest;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Admin da loja: itens, imagens e a trava do bucket `shop-items` no storage genérico. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class ShopItemControllerTest {

	static final Path media;
	static {
		try {
			media = Files.createTempDirectory("storyverse-shop-it-");
		} catch (Exception error) {
			throw new RuntimeException(error);
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
		properties.add("app.security.require-runtime-role", () -> "false");
	}

	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String ITEMS = "/api/v1/admin/shop/items";
	static final String IMAGES = "/api/v1/admin/shop/images";
	static final String MEDIA_PREFIX = "https://api.example.test/api/v1/storage/public/shop-items/";

	@Autowired MockMvc mvc;
	@Autowired AuthService auth;
	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ShopItemRepository repository;

	AuthResponse user() {
		return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Loja"));
	}

	UUID id(AuthResponse session) {
		return UUID.fromString(session.user().id().toString());
	}

	AuthResponse userWithRole(String role) {
		AuthResponse session = user();
		jdbc.sql("insert into public.user_roles(user_id, role) values (:id, :role)")
				.param("id", id(session)).param("role", role).update();
		return session;
	}

	String bearer(AuthResponse session) {
		return "Bearer " + session.accessToken();
	}

	MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
	}

	static Map<String, Object> badge(String status, String imageUrl) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("category", "badge");
		body.put("tier", "common");
		body.put("name", "Insígnia " + UUID.randomUUID().toString().substring(0, 8));
		body.put("description", "Insígnia de teste");
		body.put("price", 100);
		body.put("status", status);
		body.put("image_url", imageUrl);
		return body;
	}

	JsonNode body(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	UUID create(AuthResponse admin, Map<String, Object> item) throws Exception {
		JsonNode created = body(mvc.perform(json(post(ITEMS), item).header("Authorization", bearer(admin)))
				.andExpect(status().isCreated()));
		return UUID.fromString(created.get("id").asString());
	}

	void own(UUID item) {
		UUID buyer = jdbc.sql("insert into auth.users (email) values (:e) returning id")
				.param("e", UUID.randomUUID() + "@example.invalid").query(UUID.class).single();
		jdbc.sql("insert into public.shop_ownership (user_id, item_id, price_paid, ref) values (:u, :i, 100, :r)")
				.param("u", buyer).param("i", item).param("r", "ref-" + UUID.randomUUID()).update();
	}

	<T> T asRuntime(UUID user, Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			DbSession.setUser(jdbc, user);
			return work.get();
		});
	}

	@Test
	void nonAdminGetsForbiddenOnEveryRoute() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		UUID item = create(admin, badge("draft", null));
		for (AuthResponse session : List.of(user(), userWithRole("moderator"), userWithRole("support"))) {
			String token = bearer(session);
			mvc.perform(get(ITEMS).header("Authorization", token)).andExpect(status().isForbidden());
			mvc.perform(json(post(ITEMS), badge("draft", null)).header("Authorization", token))
					.andExpect(status().isForbidden());
			mvc.perform(json(put(ITEMS + "/" + item), badge("draft", null)).header("Authorization", token))
					.andExpect(status().isForbidden());
			mvc.perform(delete(ITEMS + "/" + item).header("Authorization", token))
					.andExpect(status().isForbidden());
			mvc.perform(multipart(IMAGES).file(new MockMultipartFile("file", "b.png", "image/png",
					ShopImageCheckTest.png(256, 256))).param("kind", "badge").header("Authorization", token))
					.andExpect(status().isForbidden());
		}
		mvc.perform(get(ITEMS)).andExpect(status().isUnauthorized());
	}

	@Test
	void nameColorWithoutContrastIsRefused() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("category", "name_color");
		item.put("tier", "common");
		item.put("name", "Cinza escuro");
		item.put("price", 50);
		item.put("colors", List.of("#ffd166", "#333333"));
		JsonNode error = body(mvc.perform(json(post(ITEMS), item).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest()));
		assertTrue(error.get("error").asString().contains("contraste"), error.toString());

		item.put("colors", List.of("#ffd166", "#f4a261"));
		create(admin, item);
	}

	@Test
	void epicFrameNeedsAnimatedImage() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("category", "frame");
		item.put("tier", "epic");
		item.put("name", "Moldura épica");
		item.put("price", 900);
		item.put("image_url", "/shop/moldura-epica.png");
		mvc.perform(json(post(ITEMS), item).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("animada")));

		item.put("animated_url", "/shop/moldura-epica.webp");
		create(admin, item);
	}

	@Test
	void onSaleItemNeedsImage() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		mvc.perform(json(post(ITEMS), badge("on_sale", null)).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("imagem")));
		// Rascunho sem imagem pode.
		create(admin, badge("draft", null));
	}

	@Test
	void imageUrlMustBeShopPathOrShopBucket() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		for (String bad : List.of("https://evil.example/x.png", "/shop/../x.png", "/shop/X.svg",
				"javascript:alert(1)", MEDIA_PREFIX + "items/badge-" + UUID.randomUUID() + ".png",
				"https://api.example.test/api/v1/storage/public/profile-media/a/b.png")) {
			mvc.perform(json(post(ITEMS), badge("draft", bad)).header("Authorization", bearer(admin)))
					.andExpect(status().isBadRequest());
		}
		create(admin, badge("on_sale", "/shop/insignia-estrela.webp"));
	}

	@Test
	void deleteOfOwnedItemIsConflict() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		UUID owned = create(admin, badge("on_sale", "/shop/insignia-a.png"));
		own(owned);
		mvc.perform(delete(ITEMS + "/" + owned).header("Authorization", bearer(admin)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("Item já vendido: tire de venda em vez de apagar."));

		UUID free = create(admin, badge("draft", null));
		mvc.perform(delete(ITEMS + "/" + free).header("Authorization", bearer(admin)))
				.andExpect(status().isNoContent());
		assertEquals(0, jdbc.sql("select count(*) from public.shop_items where id = :id")
				.param("id", free).query(Integer.class).single());
		assertEquals(1, jdbc.sql("""
				select count(*) from public.admin_audit_log
				 where action = 'shop_item.delete' and target_id = :id
				""").param("id", free.toString()).query(Integer.class).single());
	}

	@Test
	void priceUpdateIsAudited() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		Map<String, Object> item = badge("draft", null);
		UUID id = create(admin, item);
		item.put("price", 250);
		JsonNode updated = body(mvc.perform(json(put(ITEMS + "/" + id), item).header("Authorization", bearer(admin)))
				.andExpect(status().isOk()));
		assertEquals(250, updated.get("price").asInt());

		String metadata = jdbc.sql("""
				select metadata::text from public.admin_audit_log
				 where action = 'shop_item.update' and target_type = 'shop_item' and target_id = :id
				   and actor_id = :actor
				""").param("id", id.toString()).param("actor", id(admin)).query(String.class).single();
		JsonNode price = JSON.readTree(metadata).get("changes").get("price");
		assertEquals(100, price.get("from").asInt());
		assertEquals(250, price.get("to").asInt());
		assertEquals(1, jdbc.sql("""
				select count(*) from public.admin_audit_log where action = 'shop_item.create' and target_id = :id
				""").param("id", id.toString()).query(Integer.class).single());
		assertTrue(jdbc.sql("select updated_at > created_at from public.shop_items where id = :id")
				.param("id", id).query(Boolean.class).single());
	}

	@Test
	void categoryCannotChangeAfterCreation() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		Map<String, Object> item = badge("draft", null);
		UUID id = create(admin, item);
		item.put("category", "cover");
		mvc.perform(json(put(ITEMS + "/" + id), item).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listShowsOwnersAndFilters() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		UUID owned = create(admin, badge("off_sale", "/shop/insignia-b.png"));
		own(owned);
		own(owned);
		JsonNode list = body(mvc.perform(get(ITEMS).param("category", "badge").param("status", "off_sale")
				.header("Authorization", bearer(admin))).andExpect(status().isOk()));
		JsonNode row = null;
		for (JsonNode candidate : list) {
			assertEquals("badge", candidate.get("category").asString());
			assertEquals("off_sale", candidate.get("status").asString());
			if (candidate.get("id").asString().equals(owned.toString())) row = candidate;
		}
		assertNotNull(row);
		assertEquals(2, row.get("owners").asInt());
		mvc.perform(get(ITEMS).param("status", "vendido").header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void uploadedImageIsPublicAndUsableOnlyForItsKind() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		JsonNode uploaded = body(mvc.perform(multipart(IMAGES)
				.file(new MockMultipartFile("file", "b.png", "image/png", ShopImageCheckTest.png(256, 256)))
				.param("kind", "badge").header("Authorization", bearer(admin))).andExpect(status().isOk()));
		String url = uploaded.get("url").asString();
		assertTrue(url.startsWith(MEDIA_PREFIX + "items/badge-"), url);
		mvc.perform(get(url.substring("https://api.example.test".length()))).andExpect(status().isOk());

		create(admin, badge("on_sale", url));

		Map<String, Object> cover = badge("on_sale", url);
		cover.put("category", "cover");
		mvc.perform(json(post(ITEMS), cover).header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest());

		mvc.perform(multipart(IMAGES)
				.file(new MockMultipartFile("file", "x.png", "image/png", ShopImageCheckTest.png(300, 200)))
				.param("kind", "badge").header("Authorization", bearer(admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("precisa ser quadrada")));
		assertEquals(1, jdbc.sql("""
				select count(*) from public.admin_audit_log where action = 'shop_image.upload' and actor_id = :actor
				""").param("actor", id(admin)).query(Integer.class).single());
	}

	@Test
	void animatedFrameUploadAcceptsUpToThreeMegabytes() throws Exception {
		AuthResponse admin = userWithRole("superadmin");
		byte[] animated = java.util.Arrays.copyOf(ShopImageCheckTest.animatedWebp(512, 512), 2 * 1024 * 1024);
		JsonNode uploaded = body(mvc.perform(multipart(IMAGES)
				.file(new MockMultipartFile("file", "f.webp", "image/webp", animated))
				.param("kind", "frame_animated").header("Authorization", bearer(admin))).andExpect(status().isOk()));
		assertTrue(uploaded.get("url").asString().startsWith(MEDIA_PREFIX + "items/frame-animated-"));
	}

	@Test
	void genericStorageRefusesShopItemsBucket() throws Exception {
		for (AuthResponse session : List.of(user(), userWithRole("superadmin"))) {
			String path = id(session) + "/x.png";
			mvc.perform(post("/api/v1/storage/shop-items/object").param("path", path)
					.contentType("image/png").content(ShopImageCheckTest.png(256, 256))
					.header("Authorization", bearer(session)))
					.andExpect(status().isForbidden());
			mvc.perform(delete("/api/v1/storage/shop-items/object").param("path", path)
					.header("Authorization", bearer(session)))
					.andExpect(status().isForbidden());
		}
		assertTrue(!Files.exists(media.resolve("shop-items")) || Files.walk(media.resolve("shop-items"))
				.filter(Files::isRegularFile).noneMatch(file -> file.getFileName().toString().equals("x.png")));
	}

	@Test
	void runtimeRoleWritesOnlyForSuperadmin() {
		UUID admin = id(userWithRole("superadmin"));
		UUID reader = id(user());
		Map<String, Object> fields = new HashMap<>(badge("draft", null));
		fields.put("description", "");
		fields.put("animated_url", null);
		fields.put("colors", null);
		fields.put("sort_order", 0);
		fields.put("available_from", null);
		fields.put("available_until", null);
		fields.put("stock", null);
		Map<String, Object> created = asRuntime(admin, () -> repository.insert(fields));
		UUID id = (UUID) created.get("id");
		assertEquals(1, asRuntime(admin, () -> repository.list(null, null)).stream()
				.filter(row -> id.equals(row.get("id"))).count());
		assertEquals(0, asRuntime(reader, () -> repository.list(null, null)).size());
		assertThrows(DataAccessException.class, () -> asRuntime(reader, () -> repository.insert(fields)));
		assertEquals(0, (int) asRuntime(reader, () -> repository.delete(id)));
		assertEquals(1, (int) asRuntime(admin, () -> repository.delete(id)));
	}
}
