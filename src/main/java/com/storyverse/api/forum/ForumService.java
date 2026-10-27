package com.storyverse.api.forum;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;

@Service
public class ForumService {

	private static final Pattern BOOK_KEY = Pattern.compile("^(gb:[0-9]+|cm:[0-9a-fA-F-]{36})$");
	private static final Set<String> SPOILERS = Set.of("none", "chapter", "book");
	private static final Set<String> REASONS = Set.of(
			"spam", "abuso", "assedio", "spoiler", "fora_do_topico", "direitos_autorais", "outro");
	private static final Set<String> REPORT_STATUSES = Set.of("actioned", "dismissed");
	private static final Set<String> MODERATION_STATUSES = Set.of("published", "pending", "hidden", "deleted");
	private static final int EDIT_WINDOW_MINUTES = 15;

	private final ForumRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final RateLimitGate limits;

	public ForumService(ForumRepository repository, AuthService auth, AuthzService authz, JdbcClient jdbc,
			RateLimitGate limits) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.limits = limits;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> spaces() {
		return repository.spaces();
	}

	@Transactional(readOnly = true)
	public ForumPage topics(String token, String space, String bookKey, String filter, String query,
			String cursor, Integer limit) {
		UUID viewer = optionalUser(token);
		int size = limit(limit);
		ForumCursor decoded = ForumCursor.decode(cursor);
		List<Map<String, Object>> rows = repository.topics(viewer, space, bookKey, filter, query, decoded, size);
		return page(rows, size, "last_activity_at", !"popular".equals(filter));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> topic(String token, UUID id) {
		UUID viewer = optionalUser(token);
		return visibleTopic(viewer, id);
	}

	@Transactional(readOnly = true)
	public ForumPage replies(String token, UUID topicId, String cursor, Integer limit) {
		UUID viewer = optionalUser(token);
		visibleTopic(viewer, topicId);
		int size = limit(limit);
		List<Map<String, Object>> rows = repository.replies(viewer, topicId, ForumCursor.decode(cursor), size);
		return page(rows, size, "created_at", true);
	}

	@Transactional
	public Map<String, Object> createTopic(String token, Map<String, Object> body) {
		UUID userId = requireUser(token);
		String spaceSlug = text(body.get("space"));
		if (spaceSlug == null) {
			throw new AuthException(400, "Informe a categoria.");
		}
		Map<String, Object> space = repository.spaceBySlug(spaceSlug)
				.orElseThrow(() -> new AuthException(404, "Categoria nao encontrada."));
		if (!Boolean.TRUE.equals(space.get("is_active"))) {
			throw new AuthException(409, "Esta categoria esta desativada.");
		}
		String title = title(body.get("title"));
		String content = content(body.get("body"));
		String bookKey = bookKey(text(body.get("bookKey")));
		String chapterRef = limitText(text(body.get("chapterRef")), 200);
		String spoiler = spoiler(body.get("spoilerLevel"));
		limits.check("forum-topic:" + userId, 1, Duration.ofMinutes(10));
		chargeDaily(userId, "forum_topic", 5);
		String status = trusted(userId) ? "published" : "pending";
		UUID id = repository.insertTopic(UUID.fromString(String.valueOf(space.get("id"))), userId, title, content,
				bookKey, chapterRef, spoiler, status);
		return repository.topic(userId, id).orElseThrow(() -> new AuthException(500, "Topico nao criado."));
	}

	@Transactional
	public Map<String, Object> createReply(String token, UUID topicId, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> topic = repository.topicState(topicId)
				.orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		boolean staff = authz.isStaff(userId);
		if (!staff && !"published".equals(topic.get("status"))) {
			throw new AuthException(409, "Este topico ainda nao esta publicado.");
		}
		if (!staff && Boolean.TRUE.equals(topic.get("is_locked"))) {
			throw new AuthException(403, "Este topico esta fechado para novas respostas.");
		}
		String content = content(body.get("body"));
		String spoiler = spoiler(body.get("spoilerLevel"));
		UUID parentId = uuid(body.get("parentId"));
		if (parentId != null) {
			Map<String, Object> parent = repository.replyState(parentId)
					.orElseThrow(() -> new AuthException(400, "Resposta citada nao encontrada."));
			if (!topicId.equals(parent.get("topic_id"))) {
				throw new AuthException(400, "A resposta citada pertence a outro topico.");
			}
			if (!"published".equals(parent.get("status"))) {
				throw new AuthException(400, "A resposta citada ainda nao esta publicada.");
			}
		}
		limits.check("forum-reply:" + userId, 10, Duration.ofMinutes(10));
		chargeDaily(userId, "forum_reply", 50);
		String status = trusted(userId) ? "published" : "pending";
		UUID id = repository.insertReply(topicId, userId, parentId, content, status, spoiler);
		return repository.reply(userId, id).orElseThrow(() -> new AuthException(500, "Resposta nao criada."));
	}

	@Transactional
	public Map<String, Object> updateTopic(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.topicState(id)
				.orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		boolean staff = authz.isStaff(userId);
		boolean author = userId.equals(state.get("author_id"));
		if (!staff && !author) {
			throw new AuthException(403, "Sem permissao para editar este topico.");
		}
		if (!staff) {
			requireEditable(state);
		}
		if (!staff && author && olderThanEditWindow(state.get("created_at"))) {
			throw new AuthException(403, "O prazo de edicao deste topico terminou.");
		}
		String title = body.containsKey("title") ? title(body.get("title")) : null;
		String content = body.containsKey("body") ? content(body.get("body")) : null;
		String bookKey = body.containsKey("bookKey") ? bookKey(text(body.get("bookKey"))) : null;
		String chapterRef = body.containsKey("chapterRef") ? limitText(text(body.get("chapterRef")), 200) : null;
		String spoiler = body.containsKey("spoilerLevel") ? spoiler(body.get("spoilerLevel")) : null;
		repository.updateTopicContent(id, title, content, bookKey, chapterRef, spoiler,
				statusAfterEdit(userId, staff, state.get("status")));
		return repository.topic(userId, id).orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
	}

	@Transactional
	public void deleteTopic(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.topicState(id)
				.orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		if (!userId.equals(state.get("author_id")) && !authz.isStaff(userId)) {
			throw new AuthException(403, "Sem permissao para remover este topico.");
		}
		repository.softDeleteTopic(id);
	}

	@Transactional
	public Map<String, Object> updateReply(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.replyState(id)
				.orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
		boolean staff = authz.isStaff(userId);
		boolean author = userId.equals(state.get("author_id"));
		if (!staff && !author) {
			throw new AuthException(403, "Sem permissao para editar esta resposta.");
		}
		if (!staff) {
			requireEditable(state);
		}
		if (!staff && author && olderThanEditWindow(state.get("created_at"))) {
			throw new AuthException(403, "O prazo de edicao desta resposta terminou.");
		}
		String content = body.containsKey("body") ? content(body.get("body")) : null;
		String spoiler = body.containsKey("spoilerLevel") ? spoiler(body.get("spoilerLevel")) : null;
		repository.updateReplyContent(id, content, spoiler, statusAfterEdit(userId, staff, state.get("status")));
		return repository.reply(userId, id).orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
	}

	@Transactional
	public void deleteReply(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.replyState(id)
				.orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
		if (!userId.equals(state.get("author_id")) && !authz.isStaff(userId)) {
			throw new AuthException(403, "Sem permissao para remover esta resposta.");
		}
		repository.softDeleteReply(id);
	}

	@Transactional
	public Map<String, Object> reactToTopic(String token, UUID topicId) {
		UUID userId = requireUser(token);
		visibleTopic(userId, topicId);
		boolean liked = toggleReaction(userId, "topic", topicId);
		return Map.of("liked", liked, "reactionCount", repository.reactionCount("topic", topicId));
	}

	@Transactional
	public Map<String, Object> reactToReply(String token, UUID replyId) {
		UUID userId = requireUser(token);
		visibleReply(userId, replyId);
		boolean liked = toggleReaction(userId, "reply", replyId);
		return Map.of("liked", liked, "reactionCount", repository.reactionCount("reply", replyId));
	}

	@Transactional
	public Map<String, Object> toggleBookmark(String token, UUID topicId) {
		UUID userId = requireUser(token);
		visibleTopic(userId, topicId);
		boolean saved = repository.bookmarkExists(userId, topicId);
		if (saved) {
			repository.removeBookmark(userId, topicId);
		} else {
			repository.addBookmark(userId, topicId);
		}
		return Map.of("saved", !saved);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> bookmarks(String token) {
		return repository.bookmarks(requireUser(token));
	}

	@Transactional
	public Map<String, Object> report(String token, Map<String, Object> body) {
		UUID userId = requireUser(token);
		String targetType = text(body.get("targetType"));
		UUID targetId = uuid(body.get("targetId"));
		String reason = text(body.get("reasonCode"));
		String details = limitText(text(body.get("details")), 1000);
		if (!"topic".equals(targetType) && !"reply".equals(targetType)) {
			throw new AuthException(400, "Tipo de conteudo invalido.");
		}
		if (targetId == null) {
			throw new AuthException(400, "Conteudo invalido.");
		}
		if (reason == null || !REASONS.contains(reason)) {
			throw new AuthException(400, "Motivo da denuncia invalido.");
		}
		if ("topic".equals(targetType)) {
			visibleTopic(userId, targetId);
		} else {
			visibleReply(userId, targetId);
		}
		// Denuncia nao custa nada a quem envia: sem limite, uma conta enchia a fila da moderacao.
		limits.check("forum-report:" + userId, 10, Duration.ofMinutes(10));
		try {
			return repository.insertReport(userId, targetType, targetId, reason, details)
					.orElseThrow(() -> new AuthException(500, "Denuncia nao registrada."));
		} catch (DuplicateKeyException error) {
			throw new AuthException(409, "Voce ja denunciou este conteudo.");
		}
	}

	@Transactional
	public Map<String, Object> acceptReply(String token, UUID topicId, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> topic = repository.topicState(topicId)
				.orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		if (!userId.equals(topic.get("author_id")) && !authz.isStaff(userId)) {
			throw new AuthException(403, "Somente o autor ou a moderacao pode marcar a resposta.");
		}
		UUID replyId = uuid(body.get("replyId"));
		if (replyId == null) {
			throw new AuthException(400, "Informe a resposta.");
		}
		Map<String, Object> reply = repository.replyState(replyId)
				.orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
		if (!topicId.equals(reply.get("topic_id"))) {
			throw new AuthException(400, "A resposta pertence a outro topico.");
		}
		if (!"published".equals(reply.get("status"))) {
			throw new AuthException(409, "A resposta precisa estar publicada.");
		}
		repository.acceptReply(topicId, replyId, userId);
		// A trilha de auditoria só aceita INSERT da equipe (RLS). O aceite do autor
		// fica gravado no próprio tópico (accepted_by/accepted_at).
		if (authz.isStaff(userId)) {
			authz.audit(userId, "forum.reply.accept", "forum_reply", replyId.toString(),
					Map.of("topicId", topicId.toString()));
		}
		return repository.topic(userId, topicId).orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> adminReports(String token, String status, Integer limit) {
		UUID userId = requireUser(token);
		authz.requireStaff(userId);
		return repository.reports(status, limit(limit, 100));
	}

	@Transactional
	public Map<String, Object> resolveReport(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		authz.requireRole(userId, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		String status = text(body.get("status"));
		if (status == null || !REPORT_STATUSES.contains(status)) {
			throw new AuthException(400, "Situacao da denuncia invalida.");
		}
		String note = limitText(text(body.get("resolutionNote")), 1000);
		repository.report(id).orElseThrow(() -> new AuthException(404, "Denuncia nao encontrada."));
		repository.resolveReport(id, status, note, userId);
		authz.audit(userId, "forum.report.resolve", "forum_report", id.toString(),
				Map.of("status", status, "note", note == null ? "" : note));
		return repository.report(id).orElseThrow(() -> new AuthException(404, "Denuncia nao encontrada."));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> adminTopics(String token, String status, Integer limit) {
		UUID userId = requireUser(token);
		authz.requireStaff(userId);
		return repository.adminTopics(status, limit(limit, 100));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> adminReplies(String token, String status, Integer limit) {
		UUID userId = requireUser(token);
		authz.requireStaff(userId);
		return repository.adminReplies(status, limit(limit, 100));
	}

	@Transactional
	public Map<String, Object> moderateTopic(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		authz.requireRole(userId, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		repository.topicState(id).orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		String status = text(body.get("status"));
		if (status != null && !MODERATION_STATUSES.contains(status)) {
			throw new AuthException(400, "Situacao do topico invalida.");
		}
		Boolean pinned = bool(body.get("pinned"));
		Boolean locked = bool(body.get("locked"));
		repository.moderateTopic(id, status, pinned, locked);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("status", status == null ? "" : status);
		metadata.put("pinned", pinned == null ? "" : pinned);
		metadata.put("locked", locked == null ? "" : locked);
		authz.audit(userId, "forum.topic.moderate", "forum_topic", id.toString(), metadata);
		return repository.topic(userId, id).orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
	}

	@Transactional
	public Map<String, Object> moderateReply(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		authz.requireRole(userId, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		repository.replyState(id).orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
		String status = text(body.get("status"));
		if (status == null || !MODERATION_STATUSES.contains(status)) {
			throw new AuthException(400, "Situacao da resposta invalida.");
		}
		repository.moderateReply(id, status);
		authz.audit(userId, "forum.reply.moderate", "forum_reply", id.toString(), Map.of("status", status));
		return repository.reply(userId, id).orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
	}

	private boolean toggleReaction(UUID userId, String targetType, UUID targetId) {
		if (repository.reactionExists(userId, targetType, targetId)) {
			repository.removeReaction(userId, targetType, targetId);
			return false;
		}
		repository.addReaction(userId, targetType, targetId);
		return true;
	}

	private boolean trusted(UUID userId) {
		return authz.isStaff(userId) || repository.trusted(userId);
	}

	/**
	 * Topico que o leitor pode ver: publicado, ou do proprio autor, ou para a equipe. O RLS ja
	 * filtra isso no papel de runtime; a checagem aqui nao depende dele.
	 */
	private Map<String, Object> visibleTopic(UUID viewer, UUID id) {
		Map<String, Object> topic = repository.topic(viewer, id)
				.orElseThrow(() -> new AuthException(404, "Topico nao encontrado."));
		if (!visibleTo(viewer, topic)) {
			throw new AuthException(404, "Topico nao encontrado.");
		}
		return topic;
	}

	private Map<String, Object> visibleReply(UUID viewer, UUID id) {
		Map<String, Object> reply = repository.reply(viewer, id)
				.orElseThrow(() -> new AuthException(404, "Resposta nao encontrada."));
		if (!visibleTo(viewer, reply)) {
			throw new AuthException(404, "Resposta nao encontrada.");
		}
		return reply;
	}

	private boolean visibleTo(UUID viewer, Map<String, Object> row) {
		if ("published".equals(row.get("status"))) {
			return true;
		}
		if (viewer == null) {
			return false;
		}
		boolean own = viewer.equals(row.get("author_id")) && !"deleted".equals(row.get("status"));
		return own || authz.isStaff(viewer);
	}

	/** Autor nao edita o que foi removido ou ocultado pela moderacao. */
	private static void requireEditable(Map<String, Object> state) {
		Object status = state.get("status");
		if ("deleted".equals(status) || state.get("deleted_at") != null) {
			throw new AuthException(404, "Conteudo nao encontrado.");
		}
		if ("hidden".equals(status)) {
			throw new AuthException(403, "Este conteudo foi ocultado pela moderacao.");
		}
	}

	/**
	 * Quem ainda passa pela fila e edita um texto ja aprovado volta para a fila: sem isso dava
	 * para publicar algo inofensivo, esperar a aprovacao e trocar o texto dentro do prazo.
	 */
	private String statusAfterEdit(UUID userId, boolean staff, Object current) {
		if (staff || !"published".equals(current) || repository.trusted(userId)) {
			return null;
		}
		return "pending";
	}

	private void chargeDaily(UUID userId, String feature, int max) {
		var used = jdbc.sql("""
				insert into public.api_daily_usage (user_id, day, feature, used)
				values (:uid, (now() at time zone 'America/Sao_Paulo')::date, :feature, 1)
				on conflict (user_id, day, feature) do update
				   set used = api_daily_usage.used + 1
				 where api_daily_usage.used < :max
				returning used
				""").param("uid", userId).param("feature", feature).param("max", max)
				.query(Integer.class).optional();
		if (used.isEmpty()) {
			throw new AuthException(429, "Limite diario de publicacoes atingido.");
		}
	}

	private UUID requireUser(String token) {
		UUID id = auth.authenticate(token);
		DbSession.setUser(jdbc, id);
		return id;
	}

	private UUID optionalUser(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		try {
			UUID id = auth.authenticate(token);
			DbSession.setUser(jdbc, id);
			return id;
		} catch (AuthException error) {
			return null;
		}
	}

	private static ForumPage page(List<Map<String, Object>> rows, int limit, String timeField, boolean withNext) {
		String next = null;
		if (withNext && rows.size() == limit) {
			Map<String, Object> last = rows.get(rows.size() - 1);
			Object time = last.get(timeField);
			Object id = last.get("id");
			if (time != null && id != null) {
				next = ForumCursor.from(Instant.parse(String.valueOf(time)), UUID.fromString(String.valueOf(id)),
						Boolean.TRUE.equals(last.get("is_pinned"))).encode();
			}
		}
		return new ForumPage(rows, next);
	}

	private static boolean olderThanEditWindow(Object createdAt) {
		if (createdAt == null) {
			return true;
		}
		return Instant.parse(String.valueOf(createdAt)).isBefore(Instant.now().minus(Duration.ofMinutes(EDIT_WINDOW_MINUTES)));
	}

	private static int limit(Integer limit) {
		return limit(limit, 20);
	}

	private static int limit(Integer limit, int fallback) {
		if (limit == null) {
			return fallback;
		}
		return Math.max(1, Math.min(50, limit));
	}

	private static String title(Object value) {
		String text = text(value);
		if (text == null || text.length() < 3 || text.length() > 160) {
			throw new AuthException(400, "O titulo precisa ter entre 3 e 160 caracteres.");
		}
		return text;
	}

	private static String content(Object value) {
		String text = text(value);
		if (text == null || text.length() > 20000) {
			throw new AuthException(400, "O texto precisa ter entre 1 e 20000 caracteres.");
		}
		return text;
	}

	private static String spoiler(Object value) {
		String text = text(value);
		if (text == null) {
			return "none";
		}
		if (!SPOILERS.contains(text)) {
			throw new AuthException(400, "Nivel de spoiler invalido.");
		}
		return text;
	}

	private static String bookKey(String value) {
		if (value == null) {
			return null;
		}
		if (!BOOK_KEY.matcher(value).matches()) {
			throw new AuthException(400, "Livro invalido.");
		}
		return value;
	}

	private static String limitText(String value, int max) {
		if (value == null) {
			return null;
		}
		if (value.length() > max) {
			throw new AuthException(400, "Texto grande demais.");
		}
		return value;
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static UUID uuid(Object value) {
		if (value == null || String.valueOf(value).isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(String.valueOf(value));
		} catch (IllegalArgumentException error) {
			throw new AuthException(400, "Identificador invalido.");
		}
	}

	private static Boolean bool(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value == null) {
			return null;
		}
		return Boolean.parseBoolean(String.valueOf(value));
	}
}
