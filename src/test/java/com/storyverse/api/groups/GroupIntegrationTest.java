package com.storyverse.api.groups;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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

import com.storyverse.api.push.PushService;
import com.storyverse.api.push.PushTestKeys;

import com.storyverse.api.account.AccountService;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthResponse;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.SignupRequest;
import com.storyverse.api.common.DbSession;

/** Grupos com chat (V33): entrada, convite, mensagens, reações, denúncias e permissões. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class GroupIntegrationTest {
	static final Path media;
	static {
		try {
			media = Files.createTempDirectory("storyverse-groups-it-");
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
	@Autowired GroupService groups;
	@Autowired AccountService account;
	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;

	/** Roda com o papel de runtime da VPS (sem superusuário): a RLS vale de verdade. */
	<T> T asRuntime(java.util.function.Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			return action.get();
		});
	}

	/** Conta com a feature de grupos e com mais de 24 h (pode criar grupo público). */
	String member() {
		AuthResponse session = (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Leitor"));
		UUID id = UUID.fromString(session.user().id().toString());
		jdbc.sql("insert into public.feature_access(user_id, feature) values (:id, 'groups')").param("id", id).update();
		jdbc.sql("update auth.users set created_at = now() - interval '2 days' where id = :id").param("id", id).update();
		return session.accessToken();
	}

	UUID idOf(String token) {
		return auth.authenticate(token);
	}

	Map<String, Object> newGroup(String owner, String visibility) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Clube " + UUID.randomUUID().toString().substring(0, 6));
		body.put("visibility", visibility);
		return groups.create(owner, body);
	}

	Map<String, Object> send(String token, UUID group, String text) {
		return groups.send(token, group, Map.of("body", text, "clientId", UUID.randomUUID().toString()));
	}

	static UUID id(Map<String, Object> row) {
		return UUID.fromString(String.valueOf(row.get("id")));
	}

	static long messageId(Map<String, Object> row) {
		return ((Number) row.get("id")).longValue();
	}

	@Test
	void withoutFeatureEverythingIsBlocked() {
		AuthResponse session = (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Sem"));
		AuthException denied = assertThrows(AuthException.class, () -> groups.myGroups(session.accessToken()));
		assertEquals("FEATURE_DISABLED", denied.code());
	}

	@Test
	void publicGroupFlowWithUnreadAndMessages() {
		String owner = member(), reader = member();
		Map<String, Object> group = newGroup(owner, "public");
		UUID groupId = id(group);
		assertEquals("owner", group.get("my_role"));
		assertNotNull(group.get("invite_code"));

		// Antes de entrar: vê o grupo na lista pública, mas não as mensagens nem os membros.
		assertTrue(groups.publicGroups(reader, null, null, null).stream().anyMatch(g -> groupId.equals(g.get("id"))));
		assertEquals(List.of(), groups.group(reader, groupId).get("members"));
		assertThrows(AuthException.class, () -> groups.messages(reader, groupId, null, null, null));

		send(owner, groupId, "mensagem antiga");
		Map<String, Object> joined = groups.join(reader, groupId);
		assertEquals(Boolean.TRUE, joined.get("is_member"));
		assertNull(joined.get("invite_code"), "membro comum não vê o convite");
		assertEquals(0L, ((Number) joined.get("unread")).longValue(), "histórico de antes de entrar não conta");

		send(owner, groupId, "olá, clube");
		Map<String, Object> mine = groups.myGroups(reader).stream().filter(g -> groupId.equals(g.get("id"))).findFirst().orElseThrow();
		assertEquals(1L, ((Number) mine.get("unread")).longValue());
		assertEquals("olá, clube", mine.get("last_body"));

		List<Map<String, Object>> messages = groups.messages(reader, groupId, null, null, null);
		assertEquals(2, messages.size());
		groups.markRead(reader, groupId, Map.of("lastId", messageId(messages.get(1))));
		Map<String, Object> afterRead = groups.myGroups(reader).stream().filter(g -> groupId.equals(g.get("id"))).findFirst().orElseThrow();
		assertEquals(0L, ((Number) afterRead.get("unread")).longValue());

		// Reconexão: só o que veio depois do último id visto.
		send(owner, groupId, "nova");
		List<Map<String, Object>> after = groups.messages(reader, groupId, null, messageId(messages.get(1)), null);
		assertEquals(1, after.size());
		assertEquals("nova", after.get(0).get("body"));
	}

	@Test
	void typingOnlyForMembers() {
		String owner = member(), stranger = member();
		UUID groupId = id(newGroup(owner, "private"));
		assertDoesNotThrow(() -> groups.typing(owner, groupId));
		assertThrows(AuthException.class, () -> groups.typing(stranger, groupId));
	}

	/**
	 * Quem ainda não é membro não enxerga o grupo privado pela RLS: o convite precisa achar o
	 * grupo mesmo assim (era o "convite inválido ou expirado" em produção), e o limite de membros
	 * precisa contar todo mundo, não só a linha da própria conta.
	 */
	@Test
	void inviteWorksUnderTheRuntimeRoleForSomeoneOutsideTheGroup() {
		String owner = member(), friend = member(), late = member();
		Map<String, Object> group = newGroup(owner, "private");
		UUID groupId = id(group);
		String code = String.valueOf(group.get("invite_code"));
		Map<String, Object> joined = asRuntime(() -> groups.joinByInvite(friend, code));
		assertEquals(groupId, joined.get("id"));
		assertEquals(Boolean.TRUE, joined.get("is_member"));
		assertEquals(2, ((List<?>) joined.get("members")).size());
		assertThrows(AuthException.class, () -> asRuntime(() -> groups.joinByInvite(late, "codigo-que-nao-existe-123")));

		// Grupo cheio: quem chega de fora também é barrado.
		for (int i = 0; i < GroupService.MAX_MEMBERS - 2; i++) {
			jdbc.sql("insert into auth.users (id, email) values (:id, :email)")
					.param("id", UUID.randomUUID()).param("email", UUID.randomUUID() + "@example.invalid").update();
		}
		jdbc.sql("""
				insert into public.chat_members (group_id, user_id, role)
				select :group, id, 'member' from auth.users
				 where id not in (select user_id from public.chat_members where group_id = :group)
				   and id <> :late
				 limit :n
				""").param("group", groupId).param("late", idOf(late)).param("n", GroupService.MAX_MEMBERS - 2).update();
		AuthException full = assertThrows(AuthException.class, () -> asRuntime(() -> groups.joinByInvite(late, code)));
		assertEquals(409, full.status());
	}

	@Test
	void privateGroupNeedsInviteAndResendIsIdempotent() {
		String owner = member(), friend = member();
		Map<String, Object> group = newGroup(owner, "private");
		UUID groupId = id(group);
		assertThrows(AuthException.class, () -> groups.group(friend, groupId));
		assertThrows(AuthException.class, () -> groups.join(friend, groupId));
		assertTrue(groups.publicGroups(friend, null, null, null).stream().noneMatch(g -> groupId.equals(g.get("id"))));

		groups.joinByInvite(friend, String.valueOf(group.get("invite_code")));
		String clientId = UUID.randomUUID().toString();
		Map<String, Object> first = groups.send(friend, groupId, Map.of("body", "oi", "clientId", clientId));
		Map<String, Object> again = groups.send(friend, groupId, Map.of("body", "oi", "clientId", clientId));
		assertEquals(first.get("id"), again.get("id"));

		// Trocar o convite invalida o link antigo.
		String oldCode = String.valueOf(group.get("invite_code"));
		groups.rotateInvite(owner, groupId);
		String stranger = member();
		assertThrows(AuthException.class, () -> groups.joinByInvite(stranger, oldCode));
	}

	@Test
	void removedMemberCannotComeBackAndRolesAreEnforced() {
		String owner = member(), mod = member(), troll = member();
		UUID groupId = id(newGroup(owner, "public"));
		groups.join(mod, groupId);
		groups.join(troll, groupId);
		groups.setRole(owner, groupId, idOf(mod), Map.of("role", "moderator"));

		assertThrows(AuthException.class, () -> groups.removeMember(troll, groupId, idOf(mod)));
		assertThrows(AuthException.class, () -> groups.removeMember(mod, groupId, idOf(owner)));
		groups.removeMember(mod, groupId, idOf(troll));
		AuthException back = assertThrows(AuthException.class, () -> groups.join(troll, groupId));
		assertEquals(403, back.status());
		assertThrows(AuthException.class, () -> send(troll, groupId, "voltei"));

		// Dono não sai com gente no grupo; passa a posse e aí sai.
		assertThrows(AuthException.class, () -> groups.leave(owner, groupId));
		groups.setRole(owner, groupId, idOf(mod), Map.of("role", "owner"));
		groups.leave(owner, groupId);
		assertEquals("owner", groups.group(mod, groupId).get("my_role"));
	}

	@Test
	void reactionsDeletionAndReportsAutoHide() {
		String owner = member();
		UUID groupId = id(newGroup(owner, "public"));
		String[] readers = { member(), member(), member(), member() };
		for (String r : readers) groups.join(r, groupId);
		String author = readers[0];
		long msg = messageId(send(author, groupId, "mensagem ofensiva"));

		List<Map<String, Object>> reactions = groups.react(readers[1], msg, "🔥", true);
		assertEquals(1L, ((Number) reactions.get(0).get("count")).longValue());
		assertEquals(Boolean.TRUE, reactions.get(0).get("mine"));
		assertThrows(AuthException.class, () -> groups.react(readers[1], msg, "💩", true));

		assertThrows(AuthException.class, () -> groups.report(author, msg, Map.of("reasonCode", "spam")));
		for (int i = 1; i <= 3; i++) groups.report(readers[i], msg, Map.of("reasonCode", "abuso"));
		assertThrows(AuthException.class, () -> groups.report(readers[1], msg, Map.of("reasonCode", "abuso")));

		Map<String, Object> seenByOther = groups.messages(owner, groupId, null, null, null).get(0);
		assertEquals(Boolean.TRUE, seenByOther.get("hidden"));
		assertNull(seenByOther.get("body"));
		Map<String, Object> seenByAuthor = groups.messages(author, groupId, null, null, null).get(0);
		assertEquals("mensagem ofensiva", seenByAuthor.get("body"));

		// Membro comum não apaga a mensagem dos outros; o dono apaga.
		assertThrows(AuthException.class, () -> groups.deleteMessage(readers[2], msg));
		groups.deleteMessage(owner, msg);
		Map<String, Object> deleted = groups.messages(author, groupId, null, null, null).get(0);
		assertEquals(Boolean.TRUE, deleted.get("deleted"));
		assertNull(deleted.get("body"));
	}

	/** A foto do perfil aparece no chat só se for um arquivo da própria pasta no Storage. */
	@Test
	void profilePhotoOnlyFromTheOwnStorageFolder() {
		String owner = member(), outsider = member();
		UUID groupId = id(newGroup(owner, "public"));
		groups.join(outsider, groupId);
		String mine = "https://api.example.test/api/v1/storage/public/profile-media/" + idOf(owner) + "/avatar?v=1";
		setPhoto(owner, mine);
		setPhoto(outsider, "https://rastreador.example/pixel.png");
		send(owner, groupId, "com foto");
		send(outsider, groupId, "foto de fora");

		List<Map<String, Object>> messages = groups.messages(owner, groupId, null, null, null);
		assertEquals(mine, messages.get(0).get("author_photo"));
		assertNull(messages.get(1).get("author_photo"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> members = (List<Map<String, Object>>) groups.group(owner, groupId).get("members");
		assertEquals(mine, members.stream().filter(m -> idOf(owner).equals(m.get("user_id"))).findFirst().orElseThrow().get("photo"));
		assertNull(members.stream().filter(m -> idOf(outsider).equals(m.get("user_id"))).findFirst().orElseThrow().get("photo"));
	}

	void setPhoto(String token, String url) {
		jdbc.sql("update auth.users set raw_user_meta_data = raw_user_meta_data || jsonb_build_object('photo', :url) where id = :id")
				.param("url", url).param("id", idOf(token)).update();
	}

	@Test
	void newAccountsOnlyCreatePrivateGroupsAndExportHasMessages() throws Exception {
		AuthResponse session = (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID() + "@example.invalid", "test-secret", "Nova"));
		UUID id = UUID.fromString(session.user().id().toString());
		jdbc.sql("insert into public.feature_access(user_id, feature) values (:id, 'groups')").param("id", id).update();
		String token = session.accessToken();
		assertThrows(AuthException.class, () -> newGroup(token, "public"));
		UUID groupId = id(newGroup(token, "private"));
		send(token, groupId, "para a exportação");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> exported = (List<Map<String, Object>>) account.export(token).get("groupMessages");
		assertEquals("para a exportação", exported.get(0).get("body"));
	}

	@Test
	void groupPhotoOnlyFromTheEditorsOwnStorageFolder() {
		String owner = member(), other = member();
		UUID groupId = id(newGroup(owner, "public"));
		String base = "https://api.example.test/api/v1/storage/public/profile-media/";
		String mine = base + idOf(owner) + "/grupos/" + UUID.randomUUID() + "?v=123";
		Map<String, Object> updated = groups.update(owner, groupId, Map.of("photoUrl", mine, "description", "Nova descrição"));
		assertEquals(mine, updated.get("photo_url"));
		assertEquals("Nova descrição", updated.get("description"));
		// Editar outro campo não apaga a foto.
		assertEquals(mine, groups.update(owner, groupId, Map.of("name", "Outro nome")).get("photo_url"));
		String theirs = base + idOf(other) + "/grupos/" + UUID.randomUUID();
		assertThrows(AuthException.class, () -> groups.update(owner, groupId, Map.of("photoUrl", theirs)));
		assertThrows(AuthException.class, () -> groups.update(owner, groupId, Map.of("photoUrl", "https://evil.example/x.png")));
		assertThrows(AuthException.class, () -> groups.update(other, groupId, Map.of("description", "invasão")));
		Map<String, Object> cleared = new HashMap<>();
		cleared.put("photoUrl", null);
		assertNull(groups.update(owner, groupId, cleared).get("photo_url"));
	}

	@Test
	void chatBackgroundIsAPresetOrTheEditorsOwnImage() {
		String owner = member(), other = member();
		UUID groupId = id(newGroup(owner, "public"));
		assertEquals("preset:floresta", groups.update(owner, groupId, Map.of("background", "preset:floresta")).get("background"));
		assertThrows(AuthException.class, () -> groups.update(owner, groupId, Map.of("background", "preset:inventado")));
		assertThrows(AuthException.class, () -> groups.update(owner, groupId, Map.of("background", "https://evil.example/fundo.png")));
		String base = "https://api.example.test/api/v1/storage/public/profile-media/";
		String mine = base + idOf(owner) + "/grupos/" + UUID.randomUUID();
		assertEquals(mine, groups.update(owner, groupId, Map.of("background", mine)).get("background"));
		assertThrows(AuthException.class, () -> groups.update(owner, groupId, Map.of("background", base + idOf(other) + "/grupos/" + UUID.randomUUID())));
		// Editar outro campo mantém o fundo; null volta ao padrão.
		assertEquals(mine, groups.update(owner, groupId, Map.of("name", "Outro nome")).get("background"));
		Map<String, Object> cleared = new HashMap<>();
		cleared.put("background", null);
		assertNull(groups.update(owner, groupId, cleared).get("background"));
	}

	@Test
	void clubShowsMemberProgressOnTheBook() {
		String owner = member();
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Clube Holmes");
		body.put("visibility", "public");
		body.put("bookKey", "gb:1661");
		body.put("bookTitle", "As Aventuras de Sherlock Holmes");
		body.put("goalChapter", 3);
		body.put("goalUntil", "2026-12-01");
		Map<String, Object> group = groups.create(owner, body);
		assertEquals(3, ((Number) group.get("goal_chapter")).intValue());
		jdbc.sql("""
				insert into public.library_entries(user_id, book_key, source, status, progress_percent, title)
				values (:id, 'gb:1661', 'gutenberg', 'reading', 42, 'As Aventuras de Sherlock Holmes')
				""").param("id", idOf(owner)).update();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> members = (List<Map<String, Object>>) groups.group(owner, id(group)).get("members");
		assertEquals(42, ((Number) members.get(0).get("progress_percent")).intValue());
		assertThrows(AuthException.class, () -> groups.create(owner, Map.of("name", "Livro ruim", "visibility", "public", "bookKey", "x:1")));
	}

	/* ------------------------- Notificação no celular (V37) ------------------------- */

	@Autowired PushService push;

	String pushAccount() {
		return member();
	}

	static Map<String, Object> subscription(String endpoint) {
		byte[] secret = new byte[16];
		new SecureRandom().nextBytes(secret);
		String p256dh = PushTestKeys.browserPublicKey();
		return Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", PushTestKeys.b64(secret)));
	}

	<T> T asRuntimeUser(UUID user, Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			DbSession.setUser(jdbc, user);
			return action.get();
		});
	}

	@Test
	void vapidKeyIsGeneratedOnceAndKept() {
		String first = push.publicKey();
		assertEquals(65, PushTestKeys.unb64(first).length);
		assertEquals(first, push.publicKey());
		assertEquals(1, jdbc.sql("select count(*) from public.push_vapid_keys").query(Integer.class).single());
	}

	@Test
	void devicesSubscribeOnlyToBrowserPushServicesAndMoveBetweenAccounts() {
		String ana = pushAccount(), bia = pushAccount();
		String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
		// Como na VPS: papel de runtime, com a RLS valendo.
		asRuntimeUser(idOf(ana), () -> {
			push.subscribe(ana, subscription(endpoint));
			return push.publicKey();
		});
		assertEquals(idOf(ana), owner(endpoint));

		assertThrows(AuthException.class, () -> push.subscribe(ana, subscription("https://169.254.169.254/latest")));
		Map<String, Object> badKeys = new HashMap<>(subscription("https://fcm.googleapis.com/fcm/send/x"));
		badKeys.put("keys", Map.of("p256dh", "abc", "auth", "abc"));
		assertThrows(AuthException.class, () -> push.subscribe(ana, badKeys));

		// O mesmo navegador entra com outra conta: a inscrição muda de dono.
		asRuntimeUser(idOf(bia), () -> {
			push.subscribe(bia, subscription(endpoint));
			return null;
		});
		assertEquals(idOf(bia), owner(endpoint));

		// Quem não é dono não apaga; o dono apaga.
		push.unsubscribe(ana, endpoint);
		assertEquals(idOf(bia), owner(endpoint));
		push.unsubscribe(bia, endpoint);
		assertNull(owner(endpoint));
	}

	@Test
	void groupMessageTargetsOtherMembersThatDidNotMuteTheGroup() {
		String author = pushAccount(), reader = pushAccount(), muted = pushAccount(), outsider = pushAccount();
		jdbc.sql("update auth.users set created_at = now() - interval '2 days' where id = :id").param("id", idOf(author)).update();
		UUID groupId = UUID.fromString(String.valueOf(groups.create(author, Map.of("name", "Clube do Push", "visibility", "public")).get("id")));
		groups.join(reader, groupId);
		groups.join(muted, groupId);
		groups.setMuted(muted, groupId, Map.of("muted", true));
		for (String token : List.of(author, reader, muted, outsider)) {
			push.subscribe(token, subscription("https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID()));
		}

		List<UUID> targets = asRuntimeUser(idOf(author), () -> jdbc.sql("select user_id from public.chat_push_targets(:group)")
				.param("group", groupId).query(UUID.class).list());
		assertEquals(List.of(idOf(reader)), targets, "nem quem escreveu, nem quem silenciou, nem quem é de fora");

		List<UUID> fromOutside = asRuntimeUser(idOf(outsider), () -> jdbc.sql("select user_id from public.chat_push_targets(:group)")
				.param("group", groupId).query(UUID.class).list());
		assertEquals(List.of(), fromOutside, "quem não é membro não descobre os aparelhos do grupo");

		// A conta não lê os aparelhos dos outros nem a chave privada.
		Integer visible = asRuntimeUser(idOf(reader), () -> jdbc.sql("select count(*) from public.push_subscriptions").query(Integer.class).single());
		assertEquals(1, visible);
	}

	UUID owner(String endpoint) {
		return jdbc.sql("select user_id from public.push_subscriptions where endpoint = :e").param("e", endpoint)
				.query(UUID.class).optional().orElse(null);
	}
}
