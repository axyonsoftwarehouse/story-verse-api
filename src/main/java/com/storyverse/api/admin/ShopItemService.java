package com.storyverse.api.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.storage.StorageBucket;
import com.storyverse.api.storage.StorageService;

/**
 * Admin da loja: itens (tipo × campos, cores legíveis, imagens só de origem conhecida) e envio das
 * imagens para o bucket {@code shop-items}. Só superadmin: preço mexe na economia de diamantes, como
 * a cobrança e os recursos em teste. Toda escrita entra na auditoria do admin.
 */
@Service
public class ShopItemService {

	static final Set<String> CATEGORIES = Set.of("frame", "badge", "cover", "name_color");
	static final Set<String> TIERS = Set.of("common", "rare", "epic");
	static final Set<String> STATUSES = Set.of("draft", "on_sale", "off_sale");
	static final int MAX_PRICE = 1_000_000;
	static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
	/** Imagens do primeiro lote, servidas pelo próprio site (Task 4). */
	static final Pattern SEED_PATH = Pattern.compile("^/shop/[a-z0-9-]+\\.(webp|png)$");
	static final Pattern BUCKET_FILE = Pattern.compile(
			"^items/(frame|frame-animated|badge|cover)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(webp|png|jpg)$");
	/** Fundos escuros onde o nome aparece (perfil e chat). */
	static final List<String> BACKGROUNDS = List.of("#14110e", "#0e1612");
	static final double MIN_CONTRAST = 4.5;

	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ShopItemRepository repository;
	private final StorageService storage;
	private final String mediaPrefix;

	public ShopItemService(AuthService auth, AuthzService authz, JdbcClient jdbc, ShopItemRepository repository,
			StorageService storage,
			@Value("${app.public-api-url:https://api.storyverse.2.29.42.104.sslip.io}") String publicApiUrl) {
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.repository = repository;
		this.storage = storage;
		this.mediaPrefix = publicApiUrl.trim().replaceAll("/+$", "") + "/api/v1/storage/public/"
				+ StorageBucket.SHOP_ITEMS.id() + "/";
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(String token, String category, String status) {
		UUID actor = requireAdmin(token);
		String categoryFilter = blank(category) ? null : category.strip();
		if (categoryFilter != null && !CATEGORIES.contains(categoryFilter)) {
			throw new AuthException(400, "Tipo de item inválido.");
		}
		String statusFilter = blank(status) ? null : status.strip();
		if (statusFilter != null && !STATUSES.contains(statusFilter)) {
			throw new AuthException(400, "Estado de item inválido.");
		}
		DbSession.setUser(jdbc, actor);
		return repository.list(categoryFilter, statusFilter);
	}

	@Transactional
	public Map<String, Object> create(String token, Map<String, Object> body) {
		UUID actor = requireAdmin(token);
		Map<String, Object> fields = validate(body, null);
		DbSession.setUser(jdbc, actor);
		Map<String, Object> created;
		try {
			created = repository.insert(fields);
		} catch (DataIntegrityViolationException error) {
			throw new AuthException(400, "Item inválido.");
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("category", fields.get("category"));
		metadata.put("tier", fields.get("tier"));
		metadata.put("name", fields.get("name"));
		metadata.put("price", fields.get("price"));
		metadata.put("status", fields.get("status"));
		authz.audit(actor, "shop_item.create", "shop_item", created.get("id").toString(), metadata);
		return created;
	}

	@Transactional
	public Map<String, Object> update(String token, UUID id, Map<String, Object> body) {
		UUID actor = requireAdmin(token);
		DbSession.setUser(jdbc, actor);
		Map<String, Object> existing = repository.lock(id)
				.orElseThrow(() -> new AuthException(404, "Item não encontrado."));
		Map<String, Object> fields = validate(body, existing);
		Map<String, Object> updated;
		try {
			updated = repository.update(id, fields).orElseThrow(() -> new AuthException(404, "Item não encontrado."));
		} catch (DataIntegrityViolationException error) {
			throw new AuthException(400, "Item inválido.");
		}
		Map<String, Object> changes = new LinkedHashMap<>();
		for (String key : List.of("tier", "name", "description", "price", "status", "image_url", "animated_url",
				"colors", "sort_order", "available_from", "available_until", "stock")) {
			Object before = comparable(existing.get(key));
			Object after = comparable(fields.get(key));
			if (!Objects.equals(before, after)) {
				Map<String, Object> change = new LinkedHashMap<>();
				change.put("from", before);
				change.put("to", after);
				changes.put(key, change);
			}
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", fields.get("name"));
		metadata.put("changes", changes);
		authz.audit(actor, "shop_item.update", "shop_item", id.toString(), metadata);
		return updated;
	}

	@Transactional
	public void delete(String token, UUID id) {
		UUID actor = requireAdmin(token);
		DbSession.setUser(jdbc, actor);
		// A trava segura compras simultâneas: buy_shop_item trava a mesma linha antes de dar a posse.
		Map<String, Object> existing = repository.lock(id)
				.orElseThrow(() -> new AuthException(404, "Item não encontrado."));
		if (repository.owners(id) > 0) {
			throw new AuthException(409, "Item já vendido: tire de venda em vez de apagar.");
		}
		try {
			repository.delete(id);
		} catch (DataIntegrityViolationException error) {
			throw new AuthException(409, "Item já vendido: tire de venda em vez de apagar.");
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("category", existing.get("category"));
		metadata.put("name", existing.get("name"));
		authz.audit(actor, "shop_item.delete", "shop_item", id.toString(), metadata);
	}

	/** Confere a imagem pelos bytes e grava no bucket público da loja; devolve a URL pública. */
	public Map<String, String> uploadImage(String token, String kind, MultipartFile file) {
		UUID actor = requireAdmin(token);
		if (kind == null || !ShopImageCheck.KINDS.contains(kind)) {
			throw new AuthException(400, "Tipo de imagem inválido.");
		}
		if (file == null || file.isEmpty()) {
			throw new AuthException(400, "Envie o arquivo da imagem.");
		}
		if (file.getSize() > StorageBucket.SHOP_ITEMS.maxBytes()) {
			throw new AuthException(413, "Arquivo grande demais.");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException error) {
			throw new UncheckedIOException("Falha ao ler o arquivo", error);
		}
		ShopImageCheck.ImageInfo info;
		try {
			info = ShopImageCheck.check(bytes, kind);
		} catch (IllegalArgumentException error) {
			throw new AuthException(400, error.getMessage());
		}
		String extension = switch (info.mime()) {
			case "image/png" -> "png";
			case "image/jpeg" -> "jpg";
			default -> "webp";
		};
		String path = "items/" + kind.replace('_', '-') + "-" + UUID.randomUUID() + "." + extension;
		storage.writeShopImage(path, info.mime(), bytes);
		String url = mediaPrefix + path;
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("kind", kind);
		metadata.put("url", url);
		metadata.put("mime", info.mime());
		metadata.put("width", info.width());
		metadata.put("height", info.height());
		metadata.put("bytes", bytes.length);
		authz.audit(actor, "shop_image.upload", "shop_image", path, metadata);
		return Map.of("url", url);
	}

	private UUID requireAdmin(String token) {
		UUID actor = auth.authenticate(token);
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		return actor;
	}

	/**
	 * Valida o corpo inteiro (o PUT troca todos os campos editáveis). {@code existing} é a linha atual
	 * na edição: a categoria não muda e o estoque não fica abaixo do já vendido.
	 */
	Map<String, Object> validate(Map<String, Object> body, Map<String, Object> existing) {
		if (body == null) throw new AuthException(400, "Informe os dados do item.");
		String category = requiredText(body, "category", "Informe o tipo do item.");
		if (!CATEGORIES.contains(category)) throw new AuthException(400, "Tipo de item inválido.");
		if (existing != null && !category.equals(existing.get("category"))) {
			throw new AuthException(400, "O tipo do item não muda depois de criado.");
		}
		String tier = requiredText(body, "tier", "Informe o nível do item.");
		if (!TIERS.contains(tier)) throw new AuthException(400, "Nível de item inválido.");
		String name = requiredText(body, "name", "Informe o nome do item.");
		if (name.length() > 60) throw new AuthException(400, "Nome grande demais (até 60 caracteres).");
		String description = text(body, "description");
		description = description == null ? "" : description;
		if (description.length() > 200) throw new AuthException(400, "Descrição grande demais (até 200 caracteres).");
		Integer price = integer(body, "price");
		if (price == null || price < 1 || price > MAX_PRICE) {
			throw new AuthException(400, "Preço inválido (de 1 a 1.000.000 diamantes).");
		}
		String status = text(body, "status");
		status = status == null ? "draft" : status;
		if (!STATUSES.contains(status)) throw new AuthException(400, "Estado de item inválido.");
		Integer sortOrder = integer(body, "sort_order");
		OffsetDateTime from = timestamp(body, "available_from");
		OffsetDateTime until = timestamp(body, "available_until");
		if (from != null && until != null && !from.isBefore(until)) {
			throw new AuthException(400, "O fim da disponibilidade precisa ser depois do início.");
		}
		Integer stock = integer(body, "stock");
		if (stock != null && stock < 0) throw new AuthException(400, "Estoque inválido.");
		if (stock != null && existing != null && existing.get("sold") instanceof Number sold
				&& stock < sold.intValue()) {
			throw new AuthException(400, "O estoque não pode ficar abaixo do já vendido (" + sold + ").");
		}

		String imageUrl = text(body, "image_url");
		String animatedUrl = text(body, "animated_url");
		List<String> colors = colors(body);
		if ("name_color".equals(category)) {
			if (imageUrl != null || animatedUrl != null) {
				throw new AuthException(400, "Nome colorido não leva imagem, só cores.");
			}
			if (colors == null) throw new AuthException(400, "Escolha de 1 a 3 cores para o nome.");
			for (String color : colors) {
				double worst = BACKGROUNDS.stream().mapToDouble(bg -> contrast(color, bg)).min().orElse(0);
				if (worst < MIN_CONTRAST) {
					throw new AuthException(400, "A cor " + color
							+ " não tem contraste suficiente com o fundo escuro (mínimo 4,5:1).");
				}
			}
		} else {
			if (colors != null) throw new AuthException(400, "Só o nome colorido leva cores.");
			boolean epicFrame = "frame".equals(category) && "epic".equals(tier);
			if (epicFrame && (imageUrl == null || animatedUrl == null)) {
				throw new AuthException(400, "Moldura épica precisa das duas imagens: parada e animada.");
			}
			if (!epicFrame && animatedUrl != null) {
				throw new AuthException(400, "Só moldura épica tem imagem animada.");
			}
			if ("on_sale".equals(status) && imageUrl == null) {
				throw new AuthException(400, "Envie a imagem antes de pôr o item à venda.");
			}
			if (imageUrl != null) imageUrl = imageUrl(imageUrl, category);
			if (animatedUrl != null) animatedUrl = imageUrl(animatedUrl, "frame-animated");
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("category", category);
		fields.put("tier", tier);
		fields.put("name", name);
		fields.put("description", description);
		fields.put("price", price);
		fields.put("status", status);
		fields.put("image_url", imageUrl);
		fields.put("animated_url", animatedUrl);
		fields.put("colors", colors);
		fields.put("sort_order", sortOrder == null ? 0 : sortOrder);
		fields.put("available_from", from);
		fields.put("available_until", until);
		fields.put("stock", stock);
		return fields;
	}

	/**
	 * Só dois endereços: o caminho do lote inicial ({@code /shop/<arquivo>}) ou um arquivo enviado ao
	 * bucket da loja para este mesmo tipo de imagem (o tipo está no nome do arquivo) e que existe.
	 */
	private String imageUrl(String url, String kind) {
		if (SEED_PATH.matcher(url).matches()) {
			if ("frame-animated".equals(kind) && !url.endsWith(".webp")) {
				throw new AuthException(400, "A moldura animada precisa ser um WebP animado.");
			}
			return url;
		}
		if (url.startsWith(mediaPrefix)) {
			String path = url.substring(mediaPrefix.length());
			Matcher matcher = BUCKET_FILE.matcher(path);
			if (matcher.matches()) {
				if (!matcher.group(1).equals(kind)) {
					throw new AuthException(400, "Esta imagem foi enviada para outro tipo de item.");
				}
				if (!storage.exists(StorageBucket.SHOP_ITEMS, path)) {
					throw new AuthException(400, "Imagem não encontrada. Envie de novo.");
				}
				return url;
			}
		}
		throw new AuthException(400, "Imagem de origem não permitida: envie pelo painel da loja.");
	}

	/** Razão de contraste WCAG 2.x entre duas cores #rrggbb. */
	static double contrast(String a, String b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	static double luminance(String hex) {
		int rgb = Integer.parseInt(hex.substring(1), 16);
		return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF)
				+ 0.0722 * channel(rgb & 0xFF);
	}

	private static double channel(int value) {
		double c = value / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static List<String> colors(Map<String, Object> body) {
		Object raw = body.get("colors");
		if (raw == null) return null;
		if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 3) {
			throw new AuthException(400, "Escolha de 1 a 3 cores.");
		}
		List<String> colors = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String color) || !HEX.matcher(color.strip()).matches()) {
				throw new AuthException(400, "Cor inválida: use o formato #rrggbb.");
			}
			colors.add(color.strip().toLowerCase(Locale.ROOT));
		}
		return colors;
	}

	private static String requiredText(Map<String, Object> body, String key, String message) {
		String value = text(body, key);
		if (value == null) throw new AuthException(400, message);
		return value;
	}

	/** Texto sem espaços nas pontas; vazio vira null. */
	private static String text(Map<String, Object> body, String key) {
		Object raw = body.get(key);
		if (raw == null) return null;
		if (!(raw instanceof String value)) throw new AuthException(400, "Campo inválido: " + key + ".");
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

	private static Integer integer(Map<String, Object> body, String key) {
		Object raw = body.get(key);
		if (raw == null) return null;
		if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
			long value = ((Number) raw).longValue();
			if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return (int) value;
		}
		throw new AuthException(400, "Campo inválido: " + key + ".");
	}

	private static OffsetDateTime timestamp(Map<String, Object> body, String key) {
		String raw = text(body, key);
		if (raw == null) return null;
		try {
			return OffsetDateTime.parse(raw);
		} catch (DateTimeParseException error) {
			try {
				return Instant.parse(raw).atOffset(ZoneOffset.UTC);
			} catch (DateTimeParseException ignored) {
				throw new AuthException(400, "Data inválida: " + key + ".");
			}
		}
	}

	/** Valor comparável entre a linha do banco (DbRows) e o campo validado, para a auditoria. */
	private static Object comparable(Object value) {
		if (value instanceof OffsetDateTime time) return time.toInstant().toString();
		if (value instanceof Number number) return number.longValue();
		return value;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
