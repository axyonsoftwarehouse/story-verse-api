package com.storyverse.api.groups;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;
import com.storyverse.api.feature.FeatureAccessService;

/**
 * Grupos com chat (docs/SPEC_GRUPOS.md no story-verse-web). Todas as rotas exigem conta e a
 * feature {@code groups}; a RLS repete as regras de membro no papel de runtime.
 */
@Service
public class GroupService {

	static final String FEATURE = "groups";
	static final int MAX_MEMBERS = 200;
	static final int MAX_GROUPS_PER_ACCOUNT = 50;
	/** Conta nova não cria grupo público (mesma ideia da idade mínima dos presentes). */
	static final int PUBLIC_GROUP_MIN_ACCOUNT_HOURS = 24;

	private static final Pattern BOOK_KEY = Pattern.compile("^(gb:[0-9]{1,10}|cm:[0-9a-fA-F-]{36}|cat:[0-9a-fA-F-]{36})$");
	private static final Pattern INVITE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
	private static final Set<String> EMOJIS = Set.of("👍", "❤️", "😂", "😮", "😢", "🔥");
	private static final Set<String> REASONS = Set.of("spam", "abuso", "assedio", "spoiler", "outro");
	private static final Set<String> REPORT_STATUSES = Set.of("actioned", "dismissed");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final GroupRepository repository;
	private final GroupStream stream;
	private final AuthService auth;
	private final AuthzService authz;
	private final FeatureAccessService features;
	private final JdbcClient jdbc;
	private final RateLimitGate limits;

	public GroupService(GroupRepository repository, GroupStream stream, AuthService auth, AuthzService authz,
			FeatureAccessService features, JdbcClient jdbc, RateLimitGate limits) {
		this.repository = repository;
		this.stream = stream;
		this.auth = auth;
		this.authz = authz;
		this.features = features;
		this.jdbc = jdbc;
		this.limits = limits;
	}

	/* ------------------------------ Grupos ------------------------------ */

	@Transactional(readOnly = true)
	public List<Map<String, Object>> myGroups(String token) {
		return repository.myGroups(requireUser(token));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> publicGroups(String token, String query, String bookKey, Integer limit) {
		UUID userId = requireUser(token);
		String q = text(query);
		if (q != null && q.length() > 80) throw new AuthException(400, "Busca grande demais.");
		return repository.publicGroups(userId, q, bookKey(text(bookKey)), limit(limit, 30, 50));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> group(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> group = visibleGroup(userId, id);
		Map<String, Object> out = new LinkedHashMap<>(group);
		// Quem ainda não entrou num grupo público vê a capa do grupo, não quem está nele.
		out.put("members", isMember(group) ? repository.members(id) : List.of());
		return out;
	}

	@Transactional
	public Map<String, Object> create(String token, Map<String, Object> body) {
		UUID userId = requireUser(token);
		GroupFields fields = fields(body, null);
		if ("public".equals(fields.visibility()) && !repository.accountOlderThanHours(userId, PUBLIC_GROUP_MIN_ACCOUNT_HOURS)) {
			throw new AuthException(403, "Contas criadas há menos de 24 horas só podem criar grupos privados.");
		}
		if (repository.countMemberships(userId) >= MAX_GROUPS_PER_ACCOUNT) {
			throw new AuthException(409, "Você já está no número máximo de grupos.");
		}
		limits.check("group-create:" + userId, 5, Duration.ofDays(1));
		UUID id = repository.insertGroup(userId, fields, newInviteCode());
		return group(userId, id);
	}

	@Transactional
	public Map<String, Object> update(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> current = visibleGroup(userId, id);
		requireOwnerOrStaff(userId, current);
		GroupFields fields = fields(body, current);
		repository.updateGroup(id, fields);
		auditIfStaffAction(userId, current, "group.update", id);
		stream.publish(repository.activeMemberIds(id), "group", Map.of("groupId", id, "type", "updated"));
		return group(userId, id);
	}

	@Transactional
	public void delete(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> current = visibleGroup(userId, id);
		requireOwnerOrStaff(userId, current);
		List<UUID> members = repository.activeMemberIds(id);
		repository.softDeleteGroup(id);
		auditIfStaffAction(userId, current, "group.delete", id);
		stream.publish(members, "group", Map.of("groupId", id, "type", "deleted"));
	}

	@Transactional
	public Map<String, Object> join(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.groupState(id)
				.filter(g -> g.get("deleted_at") == null)
				.orElseThrow(() -> new AuthException(404, "Grupo não encontrado."));
		if (!"public".equals(state.get("visibility"))) {
			throw new AuthException(403, "Este grupo é privado. Peça o link de convite.");
		}
		return enter(userId, id);
	}

	@Transactional
	public Map<String, Object> joinByInvite(String token, String code) {
		UUID userId = requireUser(token);
		if (code == null || !INVITE.matcher(code).matches()) throw new AuthException(404, "Convite inválido ou expirado.");
		Map<String, Object> state = repository.groupByInvite(code)
				.orElseThrow(() -> new AuthException(404, "Convite inválido ou expirado."));
		return enter(userId, UUID.fromString(String.valueOf(state.get("id"))));
	}

	@Transactional
	public Map<String, Object> rotateInvite(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> current = visibleGroup(userId, id);
		requireOwnerOrStaff(userId, current);
		repository.rotateInvite(id, newInviteCode());
		return group(userId, id);
	}

	@Transactional
	public void leave(String token, UUID id) {
		UUID userId = requireUser(token);
		Map<String, Object> membership = activeMembership(id, userId);
		if ("owner".equals(membership.get("role"))) {
			if (repository.countMembers(id) > 1) {
				throw new AuthException(409, "Passe a posse do grupo para outra pessoa antes de sair, ou apague o grupo.");
			}
			// Dono sozinho: sair é apagar.
			repository.softDeleteGroup(id);
			return;
		}
		List<UUID> members = repository.activeMemberIds(id);
		repository.removeMember(id, userId);
		stream.publish(members, "group", Map.of("groupId", id, "type", "member_left", "userId", userId));
	}

	@Transactional
	public void removeMember(String token, UUID id, UUID target) {
		UUID userId = requireUser(token);
		Map<String, Object> current = visibleGroup(userId, id);
		boolean staff = authz.isStaff(userId);
		String myRole = staff ? "owner" : String.valueOf(current.get("my_role"));
		if (!"owner".equals(myRole) && !"moderator".equals(myRole)) {
			throw new AuthException(403, "Só o dono ou um moderador remove membros.");
		}
		if (userId.equals(target)) throw new AuthException(400, "Para sair do grupo, use Sair.");
		Map<String, Object> targetMembership = activeMembership(id, target);
		String targetRole = String.valueOf(targetMembership.get("role"));
		if ("owner".equals(targetRole) || ("moderator".equals(targetRole) && !"owner".equals(myRole))) {
			throw new AuthException(403, "Sem permissão para remover esta pessoa.");
		}
		List<UUID> members = repository.activeMemberIds(id);
		repository.banMember(id, target);
		auditIfStaffAction(userId, current, "group.member.remove", id);
		stream.publish(members, "group", Map.of("groupId", id, "type", "member_removed", "userId", target));
	}

	/** Dono promove a moderador, rebaixa a membro ou passa a posse ({@code owner}). */
	@Transactional
	public Map<String, Object> setRole(String token, UUID id, UUID target, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> current = visibleGroup(userId, id);
		if (!"owner".equals(current.get("my_role"))) throw new AuthException(403, "Só o dono muda os papéis.");
		if (userId.equals(target)) throw new AuthException(400, "Escolha outra pessoa.");
		activeMembership(id, target);
		String role = text(body.get("role"));
		if ("owner".equals(role)) {
			repository.setRole(id, target, "owner");
			repository.setRole(id, userId, "moderator");
			jdbc.sql("update public.chat_groups set owner_id = :owner, updated_at = now() where id = :id")
					.param("owner", target).param("id", id).update();
		} else if ("moderator".equals(role) || "member".equals(role)) {
			repository.setRole(id, target, role);
		} else {
			throw new AuthException(400, "Papel inválido.");
		}
		stream.publish(repository.activeMemberIds(id), "group", Map.of("groupId", id, "type", "updated"));
		return group(userId, id);
	}

	@Transactional
	public Map<String, Object> setMuted(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		activeMembership(id, userId);
		repository.setMuted(id, userId, Boolean.TRUE.equals(body.get("muted")));
		return group(userId, id);
	}

	@Transactional
	public void markRead(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		activeMembership(id, userId);
		long lastId = longValue(body.get("lastId"));
		if (lastId > 0) repository.markRead(id, userId, lastId);
	}

	/* ----------------------------- Mensagens ----------------------------- */

	@Transactional(readOnly = true)
	public List<Map<String, Object>> messages(String token, UUID id, Long before, Long after, Integer limit) {
		UUID userId = requireUser(token);
		activeMembership(id, userId);
		return repository.messages(userId, id, before, after, limit(limit, 50, 100));
	}

	@Transactional
	public Map<String, Object> send(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(token);
		activeMembership(id, userId);
		UUID clientId = uuid(body.get("clientId"));
		if (clientId == null) throw new AuthException(400, "Mensagem sem identificador.");
		// Reenvio da mesma mensagem (rede caiu antes da resposta): devolve a que já foi gravada.
		var existing = repository.messageByClientId(userId, clientId);
		if (existing.isPresent()) {
			return repository.message(userId, existing.get()).orElseThrow(() -> new AuthException(500, "Mensagem não encontrada."));
		}
		String text = text(body.get("body"));
		if (text == null || text.length() > 2000) throw new AuthException(400, "A mensagem precisa ter entre 1 e 2000 caracteres.");
		Integer spoiler = optionalInt(body.get("spoilerChapter"), 1, 10000, "Capítulo do spoiler inválido.");
		Long replyTo = body.get("replyTo") == null ? null : longValue(body.get("replyTo"));
		if (replyTo != null) {
			Map<String, Object> parent = repository.messageState(replyTo)
					.orElseThrow(() -> new AuthException(400, "Mensagem respondida não encontrada."));
			if (!id.equals(parent.get("group_id"))) throw new AuthException(400, "A mensagem respondida é de outro grupo.");
		}
		limits.check("group-message:" + userId, 20, Duration.ofMinutes(1));
		long messageId;
		try {
			messageId = repository.insertMessage(id, userId, text, spoiler, replyTo, clientId);
		} catch (DuplicateKeyException race) {
			messageId = repository.messageByClientId(userId, clientId).orElseThrow(() -> race);
		}
		Map<String, Object> message = repository.message(userId, messageId)
				.orElseThrow(() -> new AuthException(500, "Mensagem não gravada."));
		stream.publish(repository.activeMemberIds(id), "message", publicView(message));
		return message;
	}

	@Transactional
	public void deleteMessage(String token, long messageId) {
		UUID userId = requireUser(token);
		Map<String, Object> state = repository.messageState(messageId)
				.orElseThrow(() -> new AuthException(404, "Mensagem não encontrada."));
		UUID groupId = UUID.fromString(String.valueOf(state.get("group_id")));
		boolean staff = authz.isStaff(userId);
		boolean author = userId.equals(state.get("author_id"));
		if (!author && !staff) {
			Map<String, Object> membership = activeMembership(groupId, userId);
			String role = String.valueOf(membership.get("role"));
			if (!"owner".equals(role) && !"moderator".equals(role)) {
				throw new AuthException(403, "Sem permissão para apagar esta mensagem.");
			}
		} else if (author && !staff) {
			activeMembership(groupId, userId);
		}
		repository.softDeleteMessage(messageId, userId);
		if (staff && !author) {
			authz.audit(userId, "group.message.delete", "chat_message", String.valueOf(messageId), Map.of("groupId", groupId.toString()));
		}
		stream.publish(repository.activeMemberIds(groupId), "message_deleted", Map.of("groupId", groupId, "id", messageId));
	}

	@Transactional
	public List<Map<String, Object>> react(String token, long messageId, String emoji, boolean add) {
		UUID userId = requireUser(token);
		if (emoji == null || !EMOJIS.contains(emoji)) throw new AuthException(400, "Reação inválida.");
		Map<String, Object> state = visibleMessage(userId, messageId);
		UUID groupId = UUID.fromString(String.valueOf(state.get("group_id")));
		limits.check("group-reaction:" + userId, 60, Duration.ofMinutes(1));
		if (add) {
			repository.addReaction(messageId, userId, emoji);
		} else {
			repository.removeReaction(messageId, userId, emoji);
		}
		// Cada cliente soma ou tira 1 e marca "mine" quando o userId é o dele.
		stream.publish(repository.activeMemberIds(groupId), "reaction",
				Map.of("groupId", groupId, "messageId", messageId, "emoji", emoji, "userId", userId, "added", add));
		return repository.reactions(userId, messageId);
	}

	@Transactional
	public Map<String, Object> report(String token, long messageId, Map<String, Object> body) {
		UUID userId = requireUser(token);
		Map<String, Object> state = visibleMessage(userId, messageId);
		if (userId.equals(state.get("author_id"))) throw new AuthException(400, "Você não pode denunciar a própria mensagem.");
		String reason = text(body.get("reasonCode"));
		if (reason == null || !REASONS.contains(reason)) throw new AuthException(400, "Motivo da denúncia inválido.");
		String details = text(body.get("details"));
		if (details != null && details.length() > 1000) throw new AuthException(400, "Texto grande demais.");
		limits.check("group-report:" + userId, 10, Duration.ofMinutes(10));
		try {
			return repository.insertReport(userId, messageId, reason, details)
					.orElseThrow(() -> new AuthException(500, "Denúncia não registrada."));
		} catch (DuplicateKeyException duplicate) {
			throw new AuthException(409, "Você já denunciou esta mensagem.");
		}
	}

	/* ------------------------------ Tempo real ------------------------------ */

	public SseEmitter connect(String token) {
		UUID userId = requireUser(token);
		return stream.connect(userId);
	}

	/* ------------------------------- Admin ------------------------------- */

	@Transactional(readOnly = true)
	public List<Map<String, Object>> adminReports(String token, String status, Integer limit) {
		UUID userId = requireStaffUser(token);
		authz.requireStaff(userId);
		String s = text(status);
		if (s != null && !Set.of("pending", "actioned", "dismissed").contains(s)) throw new AuthException(400, "Situação inválida.");
		return repository.reports(s, limit(limit, 50, 100));
	}

	/** Resolve a denúncia (e as outras pendentes da mesma mensagem); {@code deleteMessage} apaga a mensagem. */
	@Transactional
	public Map<String, Object> resolveReport(String token, UUID id, Map<String, Object> body) {
		UUID userId = requireStaffUser(token);
		authz.requireRole(userId, AuthzService.SUPERADMIN, AuthzService.MODERATOR);
		String status = text(body.get("status"));
		if (status == null || !REPORT_STATUSES.contains(status)) throw new AuthException(400, "Situação da denúncia inválida.");
		String note = text(body.get("resolutionNote"));
		if (note != null && note.length() > 1000) throw new AuthException(400, "Texto grande demais.");
		Map<String, Object> report = repository.report(id).orElseThrow(() -> new AuthException(404, "Denúncia não encontrada."));
		long messageId = ((Number) report.get("message_id")).longValue();
		repository.resolveReports(messageId, status, note, userId);
		boolean deleteMessage = "actioned".equals(status) && Boolean.TRUE.equals(body.get("deleteMessage"));
		if (deleteMessage) {
			Map<String, Object> state = repository.messageState(messageId).orElseThrow();
			repository.softDeleteMessage(messageId, userId);
			UUID groupId = UUID.fromString(String.valueOf(state.get("group_id")));
			stream.publish(repository.activeMemberIds(groupId), "message_deleted", Map.of("groupId", groupId, "id", messageId));
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("status", status);
		metadata.put("deleteMessage", deleteMessage);
		metadata.put("note", note == null ? "" : note);
		authz.audit(userId, "group.report.resolve", "chat_report", id.toString(), metadata);
		return Map.of("id", id, "status", status, "messageDeleted", deleteMessage);
	}

	/* ------------------------------ Apoio ------------------------------ */

	private Map<String, Object> enter(UUID userId, UUID id) {
		var membership = repository.membership(id, userId);
		if (membership.isPresent()) {
			if (membership.get().get("banned_at") != null) throw new AuthException(403, "Você foi removido deste grupo.");
			return group(userId, id);
		}
		if (repository.countMembers(id) >= MAX_MEMBERS) throw new AuthException(409, "Este grupo está cheio.");
		if (repository.countMemberships(userId) >= MAX_GROUPS_PER_ACCOUNT) {
			throw new AuthException(409, "Você já está no número máximo de grupos.");
		}
		limits.check("group-join:" + userId, 20, Duration.ofHours(1));
		List<UUID> before = repository.activeMemberIds(id);
		repository.addMember(id, userId);
		stream.publish(before, "group", Map.of("groupId", id, "type", "member_joined", "userId", userId));
		return group(userId, id);
	}

	private Map<String, Object> group(UUID userId, UUID id) {
		Map<String, Object> group = repository.group(userId, id).orElseThrow(() -> new AuthException(404, "Grupo não encontrado."));
		Map<String, Object> out = new LinkedHashMap<>(group);
		out.put("members", isMember(group) ? repository.members(id) : List.of());
		return out;
	}

	/** Grupo que a conta pode ver: público, ou privado em que ela é membro, ou para a equipe. */
	private Map<String, Object> visibleGroup(UUID userId, UUID id) {
		Map<String, Object> group = repository.group(userId, id).orElseThrow(() -> new AuthException(404, "Grupo não encontrado."));
		if (!"public".equals(group.get("visibility")) && !isMember(group) && !authz.isStaff(userId)) {
			throw new AuthException(404, "Grupo não encontrado.");
		}
		return group;
	}

	private Map<String, Object> activeMembership(UUID groupId, UUID userId) {
		Map<String, Object> membership = repository.membership(groupId, userId)
				.orElseThrow(() -> new AuthException(403, "Entre no grupo para ver as mensagens."));
		if (membership.get("banned_at") != null) throw new AuthException(403, "Você foi removido deste grupo.");
		return membership;
	}

	/** Mensagem de um grupo em que a conta está (para reagir e denunciar). */
	private Map<String, Object> visibleMessage(UUID userId, long messageId) {
		Map<String, Object> state = repository.messageState(messageId)
				.filter(m -> m.get("deleted_at") == null)
				.orElseThrow(() -> new AuthException(404, "Mensagem não encontrada."));
		activeMembership(UUID.fromString(String.valueOf(state.get("group_id"))), userId);
		return state;
	}

	private void requireOwnerOrStaff(UUID userId, Map<String, Object> group) {
		if (!"owner".equals(group.get("my_role")) && !authz.isStaff(userId)) {
			throw new AuthException(403, "Só o dono do grupo pode fazer isso.");
		}
	}

	private void auditIfStaffAction(UUID userId, Map<String, Object> group, String action, UUID id) {
		if (!"owner".equals(group.get("my_role")) && authz.isStaff(userId)) {
			authz.audit(userId, action, "chat_group", id.toString(), Map.of());
		}
	}

	private static boolean isMember(Map<String, Object> group) {
		return Boolean.TRUE.equals(group.get("is_member"));
	}

	/** O evento vai para todos os membros: sem os campos que dependem de quem pediu. */
	private static Map<String, Object> publicView(Map<String, Object> message) {
		Map<String, Object> out = new LinkedHashMap<>(message);
		out.remove("client_id");
		out.put("reactions", List.of());
		return out;
	}

	/** Valida os campos; em edição ({@code current} != null) o que não veio fica como está. */
	private static GroupFields fields(Map<String, Object> body, Map<String, Object> current) {
		String name = pick(body, current, "name", "name");
		if (name == null || name.length() < 3 || name.length() > 60) {
			throw new AuthException(400, "O nome do grupo precisa ter entre 3 e 60 caracteres.");
		}
		String description = pick(body, current, "description", "description");
		if (description != null && description.length() > 300) throw new AuthException(400, "A descrição pode ter até 300 caracteres.");
		String visibility = pick(body, current, "visibility", "visibility");
		if (!"public".equals(visibility) && !"private".equals(visibility)) throw new AuthException(400, "Escolha público ou privado.");
		String bookKey = bookKey(pick(body, current, "bookKey", "book_key"));
		String bookTitle = bookKey == null ? null : pick(body, current, "bookTitle", "book_title");
		if (bookTitle != null && bookTitle.length() > 200) throw new AuthException(400, "Título do livro grande demais.");
		String bookCover = bookKey == null ? null : pick(body, current, "bookCover", "book_cover");
		if (bookCover != null && (bookCover.length() > 1000 || !bookCover.startsWith("https://"))) bookCover = null;
		Integer goalChapter = null;
		String goalUntil = null;
		if (bookKey != null) {
			Object chapter = body.containsKey("goalChapter") ? body.get("goalChapter") : current == null ? null : current.get("goal_chapter");
			goalChapter = optionalInt(chapter, 1, 10000, "Capítulo da meta inválido.");
			goalUntil = pick(body, current, "goalUntil", "goal_until");
			if (goalUntil != null) {
				try {
					LocalDate.parse(goalUntil);
				} catch (DateTimeParseException error) {
					throw new AuthException(400, "Data da meta inválida.");
				}
			}
		}
		return new GroupFields(name, description, visibility, bookKey, bookTitle, bookCover, goalChapter, goalUntil);
	}

	private static String pick(Map<String, Object> body, Map<String, Object> current, String field, String column) {
		if (body.containsKey(field)) return text(body.get(field));
		return current == null ? null : text(current.get(column));
	}

	private UUID requireUser(String token) {
		UUID id = auth.authenticate(token);
		DbSession.setUser(jdbc, id);
		features.require(id, FEATURE);
		DbSession.setUser(jdbc, id);
		return id;
	}

	/** Equipe modera mesmo sem a feature ligada na própria conta. */
	private UUID requireStaffUser(String token) {
		UUID id = auth.authenticate(token);
		DbSession.setUser(jdbc, id);
		return id;
	}

	private static String newInviteCode() {
		byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String bookKey(String value) {
		if (value == null) return null;
		if (!BOOK_KEY.matcher(value).matches()) throw new AuthException(400, "Livro inválido.");
		return value;
	}

	private static int limit(Integer value, int fallback, int max) {
		return value == null ? fallback : Math.max(1, Math.min(max, value));
	}

	private static Integer optionalInt(Object value, int min, int max, String error) {
		if (value == null || String.valueOf(value).isBlank()) return null;
		try {
			int n = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
			if (n < min || n > max) throw new AuthException(400, error);
			return n;
		} catch (NumberFormatException e) {
			throw new AuthException(400, error);
		}
	}

	private static long longValue(Object value) {
		if (value instanceof Number number) return number.longValue();
		try {
			return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			throw new AuthException(400, "Identificador inválido.");
		}
	}

	private static UUID uuid(Object value) {
		if (value == null || String.valueOf(value).isBlank()) return null;
		try {
			return UUID.fromString(String.valueOf(value));
		} catch (IllegalArgumentException error) {
			throw new AuthException(400, "Identificador inválido.");
		}
	}

	private static String text(Object value) {
		if (value == null) return null;
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}
}
