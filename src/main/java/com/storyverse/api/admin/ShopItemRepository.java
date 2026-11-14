package com.storyverse.api.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.storyverse.api.common.DbRows;

/**
 * Catálogo da loja para o painel ({@code public.shop_items}). O serviço define o usuário da RLS
 * (V55: só superadmin lê e grava pelo papel de runtime). Campos chegam já validados, em snake_case.
 */
@Repository
public class ShopItemRepository {

	private static final String COLUMNS = """
			i.id, i.category, i.tier, i.name, i.description, i.price, i.status, i.image_url, i.animated_url,
			i.colors, i.sort_order, i.available_from, i.available_until, i.stock, i.sold, i.created_at, i.updated_at
			""";

	private static final String OWNERS = "(select count(*) from public.shop_ownership o where o.item_id = i.id)::int as owners";

	private final JdbcClient jdbc;

	public ShopItemRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> list(String category, String status) {
		Map<String, Object> params = new HashMap<>();
		params.put("category", category);
		params.put("status", status);
		return DbRows.list(jdbc, "select " + COLUMNS + ", " + OWNERS + """
				  from public.shop_items i
				 where (cast(:category as text) is null or i.category = :category)
				   and (cast(:status as text) is null or i.status = :status)
				 order by i.category, i.sort_order, i.price, i.name
				""", params);
	}

	public Optional<Map<String, Object>> find(UUID id) {
		return DbRows.one(jdbc, "select " + COLUMNS + ", " + OWNERS + " from public.shop_items i where i.id = :id",
				Map.of("id", id));
	}

	/** Trava a linha (a compra, em buy_shop_item, trava a mesma linha) e devolve o item. */
	public Optional<Map<String, Object>> lock(UUID id) {
		return DbRows.one(jdbc, "select " + COLUMNS + " from public.shop_items i where i.id = :id for update",
				Map.of("id", id));
	}

	public int owners(UUID id) {
		return jdbc.sql("select count(*)::int from public.shop_ownership where item_id = :id")
				.param("id", id).query(Integer.class).single();
	}

	public Map<String, Object> insert(Map<String, Object> fields) {
		return DbRows.one(jdbc, """
				with i as (
				  insert into public.shop_items (category, tier, name, description, price, status, image_url,
				    animated_url, colors, sort_order, available_from, available_until, stock)
				  values (:category, :tier, :name, :description, :price, :status, :image_url,
				    :animated_url, cast(:colors as text[]), :sort_order, :available_from, :available_until, :stock)
				  returning *
				)
				""" + " select " + COLUMNS + ", 0 as owners from i", params(fields)).orElseThrow();
	}

	/** Troca todos os campos editáveis (a categoria não muda) e marca {@code updated_at}. */
	public Optional<Map<String, Object>> update(UUID id, Map<String, Object> fields) {
		Map<String, Object> params = params(fields);
		params.put("id", id);
		return DbRows.one(jdbc, """
				with i as (
				  update public.shop_items
				     set tier = :tier, name = :name, description = :description, price = :price, status = :status,
				         image_url = :image_url, animated_url = :animated_url, colors = cast(:colors as text[]),
				         sort_order = :sort_order, available_from = :available_from,
				         available_until = :available_until, stock = :stock, updated_at = now()
				   where id = :id
				  returning *
				)
				""" + " select " + COLUMNS + ", " + OWNERS + " from i", params);
	}

	public int delete(UUID id) {
		return jdbc.sql("delete from public.shop_items where id = :id").param("id", id).update();
	}

	/** Parâmetros nomeados; as cores viram literal de array (já validadas como #rrggbb). */
	private static Map<String, Object> params(Map<String, Object> fields) {
		Map<String, Object> params = new HashMap<>();
		for (String key : List.of("category", "tier", "name", "description", "price", "status", "image_url",
				"animated_url", "sort_order", "available_from", "available_until", "stock")) {
			params.put(key, fields.get(key));
		}
		Object colors = fields.get("colors");
		params.put("colors", colors instanceof List<?> list ? "{" + String.join(",", list.stream()
				.map(String::valueOf).toList()) + "}" : null);
		return params;
	}
}
