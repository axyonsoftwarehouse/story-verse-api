package com.storyverse.api.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbRows;
import com.storyverse.api.common.DbSession;

import tools.jackson.databind.JsonNode;

/** Loja de itens de perfil (V54): catálogo, compra com diamantes, posse e equipar. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class ShopIntegrationTest {

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.auth.require-email-confirmation", () -> "false");
		properties.add("app.security.require-runtime-role", () -> "false");
	}

	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired RpcService rpc;

	UUID newUser() {
		return jdbc.sql("insert into auth.users (email) values (:e) returning id")
				.param("e", UUID.randomUUID() + "@example.invalid").query(UUID.class).single();
	}

	void giveDiamonds(UUID user, int amount) {
		jdbc.sql("select public.apply_diamonds(:u, :n, 'adjustment', :ref)")
				.param("u", user).param("n", amount).param("ref", UUID.randomUUID().toString())
				.query(Integer.class).single();
	}

	UUID newItem(String name, int price) {
		return jdbc.sql("""
				insert into public.shop_items (category, tier, name, description, price, status, image_url)
				values ('frame', 'common', :name, 'Moldura de teste', :price, 'on_sale', 'https://img.invalid/f.png')
				returning id
				""").param("name", name).param("price", price).query(UUID.class).single();
	}

	String uniqueName() {
		return "Moldura " + UUID.randomUUID().toString().substring(0, 8);
	}

	int balance(UUID user) {
		return jdbc.sql("select coalesce((select balance from public.diamond_wallets where user_id = :u), 0)")
				.param("u", user).query(Integer.class).single();
	}

	/** Roda como a conta, pelo papel de runtime da VPS (a RLS vale). */
	<T> T asUser(UUID user, Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			DbSession.setUser(jdbc, user);
			return work.get();
		});
	}

	Map<String, Object> buy(UUID user, UUID item, String ref) {
		return asUser(user, () -> DbRows.one(jdbc, "select * from public.buy_shop_item(:item, :ref)",
				Map.of("item", item, "ref", ref)).orElseThrow());
	}

	List<Map<String, Object>> myItems(UUID user) {
		return asUser(user, () -> DbRows.list(jdbc, "select * from public.my_shop_items()", Map.of()));
	}

	String equip(UUID user, String category, UUID item) {
		Map<String, Object> params = new HashMap<>();
		params.put("category", category);
		params.put("item", item);
		return asUser(user, () -> {
			var spec = jdbc.sql("select public.equip_shop_item(:category, cast(:item as uuid))::text");
			for (var entry : params.entrySet()) {
				spec = spec.param(entry.getKey(), entry.getValue());
			}
			return spec.query(String.class).single();
		});
	}

	static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}

	static String ref() {
		return UUID.randomUUID().toString();
	}

	@Test
	void buyDebitsAndOwns() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);

		Map<String, Object> result = buy(user, item, ref());
		assertEquals(80, ((Number) result.get("balance")).intValue());
		assertEquals(item, result.get("item_id"));
		assertEquals(Boolean.FALSE, result.get("duplicate"));

		List<Map<String, Object>> owned = myItems(user);
		assertEquals(1, owned.size());
		assertEquals(item, owned.get(0).get("item_id"));
		assertEquals(120, ((Number) owned.get(0).get("price_paid")).intValue());
		assertEquals(Boolean.FALSE, owned.get(0).get("equipped"));
	}

	@Test
	void sameRefIsDuplicateAndChargesOnce() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);
		String ref = ref();

		buy(user, item, ref);
		Map<String, Object> again = buy(user, item, ref);
		assertEquals(Boolean.TRUE, again.get("duplicate"));
		assertEquals(80, ((Number) again.get("balance")).intValue());
		assertEquals(item, again.get("item_id"));
		assertEquals(80, balance(user));
		int purchases = jdbc.sql("select count(*) from public.diamond_ledger where user_id = :u and reason = 'purchase'")
				.param("u", user).query(Integer.class).single();
		assertEquals(1, purchases);
	}

	@Test
	void secondBuyWithNewRefIsRefused() {
		UUID user = newUser();
		giveDiamonds(user, 300);
		UUID item = newItem(uniqueName(), 120);
		buy(user, item, ref());

		DataAccessException error = assertThrows(DataAccessException.class, () -> buy(user, item, ref()));
		assertTrue(rootMessage(error).contains("já tem este item"), rootMessage(error));
		assertEquals(180, balance(user));
	}

	@Test
	void insufficientBalanceIsRefused() {
		UUID user = newUser();
		giveDiamonds(user, 50);
		UUID item = newItem(uniqueName(), 120);

		DataAccessException error = assertThrows(DataAccessException.class, () -> buy(user, item, ref()));
		assertTrue(rootMessage(error).contains("Diamantes insuficientes"), rootMessage(error));
		assertEquals(50, balance(user));
		assertTrue(myItems(user).isEmpty());
	}

	@Test
	void notOnSaleOrOutsideWindowIsRefused() {
		UUID user = newUser();
		giveDiamonds(user, 1000);
		List<String> changes = List.of(
				"status = 'draft'",
				"status = 'off_sale'",
				"available_until = now() - interval '1 minute'",
				"available_from = now() + interval '1 day'",
				"stock = 3, sold = 3");
		for (String change : changes) {
			UUID item = newItem(uniqueName(), 120);
			jdbc.sql("update public.shop_items set " + change + " where id = :id").param("id", item).update();

			DataAccessException error = assertThrows(DataAccessException.class, () -> buy(user, item, ref()), change);
			assertTrue(rootMessage(error).contains("indisponível"), change + ": " + rootMessage(error));
			var listed = asUser(user, () -> DbRows.list(jdbc, "select id, remaining from public.shop_catalog()", Map.of()))
					.stream().filter(row -> item.equals(row.get("id"))).findFirst();
			if (change.startsWith("stock")) {
				// Esgotado continua na vitrine (dentro da janela), com remaining = 0.
				assertEquals(0, ((Number) listed.orElseThrow().get("remaining")).intValue());
			} else {
				assertTrue(listed.isEmpty(), change + ": item fora de venda apareceu no catálogo");
			}
		}
		assertEquals(1000, balance(user));
	}

	@Test
	void catalogShowsOwnedAndRemaining() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);
		jdbc.sql("update public.shop_items set stock = 5 where id = :id").param("id", item).update();
		buy(user, item, ref());

		Map<String, Object> row = asUser(user, () -> DbRows.list(jdbc, "select * from public.shop_catalog()", Map.of()))
				.stream().filter(r -> item.equals(r.get("id"))).findFirst().orElseThrow();
		assertEquals(Boolean.TRUE, row.get("owned"));
		assertEquals(Boolean.FALSE, row.get("equipped"));
		assertEquals(4, ((Number) row.get("remaining")).intValue());
	}

	@Test
	void pricePaidSurvivesPriceChange() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);
		buy(user, item, ref());
		jdbc.sql("update public.shop_items set price = 300 where id = :id").param("id", item).update();

		assertEquals(120, ((Number) myItems(user).get(0).get("price_paid")).intValue());
	}

	@Test
	void equipRequiresOwnership() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);

		assertThrows(DataAccessException.class, () -> equip(user, "frame", item));

		buy(user, item, ref());
		String equipped = equip(user, "frame", item);
		assertTrue(equipped.contains("https://img.invalid/f.png"), equipped);
		assertFalse(equipped.contains("\"frame\": null"), equipped);
		assertEquals(Boolean.TRUE, myItems(user).get(0).get("equipped"));

		String cleared = equip(user, "frame", null);
		assertTrue(cleared.contains("\"frame\": null"), cleared);
		assertEquals(Boolean.FALSE, myItems(user).get(0).get("equipped"));
	}

	@Test
	void equipRefusesWrongCategory() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);
		buy(user, item, ref());

		assertThrows(DataAccessException.class, () -> equip(user, "badge", item));
	}

	@Test
	void historyNamesTheItem() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		String name = uniqueName();
		UUID item = newItem(name, 120);
		buy(user, item, ref());

		List<Map<String, Object>> history = asUser(user,
				() -> DbRows.list(jdbc, "select * from public.my_diamond_history(10)", Map.of()));
		Map<String, Object> purchase = history.stream().filter(row -> "purchase".equals(row.get("reason")))
				.findFirst().orElseThrow();
		assertEquals(name, purchase.get("item_name"));
		Map<String, Object> adjustment = history.stream().filter(row -> "adjustment".equals(row.get("reason")))
				.findFirst().orElseThrow();
		assertNull(adjustment.get("item_name"));
	}

	@Test
	void concurrentBuysChargeOnce() throws Exception {
		UUID user = newUser();
		giveDiamonds(user, 1000);
		UUID item = newItem(uniqueName(), 120);

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Callable<Object> task = () -> {
					start.await();
					try {
						return buy(user, item, ref());
					} catch (DataAccessException error) {
						return error;
					}
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			int bought = 0;
			int refused = 0;
			for (Future<Object> future : futures) {
				Object outcome = future.get();
				if (outcome instanceof DataAccessException error) {
					assertTrue(rootMessage(error).contains("já tem este item"), rootMessage(error));
					refused++;
				} else {
					bought++;
				}
			}
			assertEquals(1, bought);
			assertEquals(1, refused);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(880, balance(user));
		assertEquals(1, myItems(user).size());
		int sold = jdbc.sql("select sold from public.shop_items where id = :id").param("id", item).query(Integer.class).single();
		assertEquals(1, sold);
	}

	@Test
	void rpcExposesShopFunctions() {
		UUID user = newUser();
		giveDiamonds(user, 200);
		UUID item = newItem(uniqueName(), 120);
		jdbc.sql("update public.shop_items set colors = array['#ff0000', '#00ff00'] where id = :id")
				.param("id", item).update();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> bought = (List<Map<String, Object>>) rpc.call(user, "buy_shop_item",
				Map.of("item", item.toString(), "ref", ref()));
		assertEquals(80, ((Number) bought.get(0).get("balance")).intValue());

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> catalog = (List<Map<String, Object>>) rpc.call(user, "shop_catalog", Map.of());
		Map<String, Object> listed = catalog.stream().filter(row -> item.equals(row.get("id"))).findFirst().orElseThrow();
		assertEquals(List.of("#ff0000", "#00ff00"), listed.get("colors"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> mine = (List<Map<String, Object>>) rpc.call(user, "my_shop_items", Map.of());
		assertEquals(List.of("#ff0000", "#00ff00"), mine.get(0).get("colors"));

		JsonNode cosmetics = (JsonNode) rpc.call(user, "equip_shop_item", Map.of("category", "frame", "item", item.toString()));
		assertEquals("https://img.invalid/f.png", cosmetics.get("frame").get("image").asString());

		Map<String, Object> unequip = new HashMap<>();
		unequip.put("category", "frame");
		unequip.put("item", null);
		JsonNode cleared = (JsonNode) rpc.call(user, "equip_shop_item", unequip);
		assertTrue(cleared.get("frame").isNull());

		AuthException refused = assertThrows(AuthException.class,
				() -> rpc.call(user, "buy_shop_item", Map.of("item", item.toString(), "ref", ref())));
		assertTrue(refused.getMessage().contains("já tem este item"), refused.getMessage());
	}
}
