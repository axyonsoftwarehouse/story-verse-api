package com.storyverse.api.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Conceder/tirar diamantes pelo painel (V63): só superadmin, extrato 'adjustment' e auditoria. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class AdminGrantDiamondsIntegrationTest {

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

	String newEmail() {
		return UUID.randomUUID() + "@example.invalid";
	}

	UUID newUser(String email) {
		return jdbc.sql("insert into auth.users (email) values (:e) returning id").param("e", email)
				.query(UUID.class).single();
	}

	UUID newSuperadmin() {
		UUID user = newUser(newEmail());
		jdbc.sql("insert into public.user_roles (user_id, role) values (:u, 'superadmin')").param("u", user).update();
		return user;
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

	Map<String, Object> grant(UUID actor, String email, int amount, String note) {
		return asUser(actor, () -> DbRows.one(jdbc, "select * from public.admin_grant_diamonds(:e, :n, :note)",
				Map.of("e", email, "n", amount, "note", note)).orElseThrow());
	}

	static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}

	@Test
	void superadminCreditsAndDebitsByEmail() {
		UUID admin = newSuperadmin();
		String email = newEmail();
		UUID target = newUser(email);

		Map<String, Object> credit = grant(admin, email.toUpperCase(), 500, "Beta tester");
		assertEquals(target, credit.get("user_id"));
		assertEquals(500, ((Number) credit.get("balance")).intValue());

		Map<String, Object> debit = grant(admin, email, -120, "Correção");
		assertEquals(380, ((Number) debit.get("balance")).intValue());
		assertEquals(380, balance(target));

		List<String> refs = jdbc.sql("""
				select ref from public.diamond_ledger
				where user_id = :u and reason = 'adjustment' order by id
				""").param("u", target).query(String.class).list();
		assertEquals(2, refs.size());
		assertTrue(refs.get(0).startsWith("admin:"));
		assertNotEquals(refs.get(0), refs.get(1));

		Map<String, Object> audit = DbRows.one(jdbc, """
				select actor_id, metadata ->> 'note' as note, (metadata ->> 'amount')::int as amount,
				       (metadata ->> 'balance_after')::int as balance_after
				from public.admin_audit_log
				where action = 'diamonds.grant' and target_id = :t order by id desc limit 1
				""", Map.of("t", target.toString())).orElseThrow();
		assertEquals(admin, audit.get("actor_id"));
		assertEquals("Correção", audit.get("note"));
		assertEquals(-120, ((Number) audit.get("amount")).intValue());
		assertEquals(380, ((Number) audit.get("balance_after")).intValue());
	}

	@Test
	void sameNoteTwiceGrantsTwice() {
		UUID admin = newSuperadmin();
		String email = newEmail();
		UUID target = newUser(email);
		grant(admin, email, 10, "Bônus");
		grant(admin, email, 10, "Bônus");
		assertEquals(20, balance(target));
	}

	@Test
	void nonSuperadminIsRefused() {
		UUID common = newUser(newEmail());
		UUID moderator = newUser(newEmail());
		jdbc.sql("insert into public.user_roles (user_id, role) values (:u, 'moderator')").param("u", moderator).update();
		String email = newEmail();
		UUID target = newUser(email);

		for (UUID actor : List.of(common, moderator)) {
			DataAccessException error = assertThrows(DataAccessException.class, () -> grant(actor, email, 100, "Tentativa"));
			assertTrue(rootMessage(error).contains("Só superadmin"));
		}
		assertEquals(0, balance(target));
	}

	@Test
	void unknownEmailOverdraftAndBadInputAreRefused() {
		UUID admin = newSuperadmin();
		String email = newEmail();
		UUID target = newUser(email);

		assertTrue(rootMessage(assertThrows(DataAccessException.class,
				() -> grant(admin, newEmail(), 10, "Ninguém"))).contains("Nenhuma conta"));
		assertTrue(rootMessage(assertThrows(DataAccessException.class,
				() -> grant(admin, email, -1, "Sem saldo"))).contains("insuficientes"));
		assertTrue(rootMessage(assertThrows(DataAccessException.class,
				() -> grant(admin, email, 0, "Zero"))).contains("diferente de zero"));
		assertTrue(rootMessage(assertThrows(DataAccessException.class,
				() -> grant(admin, email, 10, " "))).contains("motivo"));
		assertEquals(0, balance(target));
		assertEquals(0, jdbc.sql("select count(*) from public.admin_audit_log where target_id = :t")
				.param("t", target.toString()).query(Integer.class).single());
	}

	@Test
	void rpcWhitelistCallsTheFunction() {
		UUID admin = newSuperadmin();
		String email = newEmail();
		UUID target = newUser(email);

		Object rows = rpc.call(admin, "admin_grant_diamonds", Map.of("p_email", email, "p_amount", 25, "p_note", "Pela API"));
		assertInstanceOf(List.class, rows);
		assertEquals(25, balance(target));

		UUID common = newUser(newEmail());
		AuthException error = assertThrows(AuthException.class,
				() -> rpc.call(common, "admin_grant_diamonds", Map.of("p_email", email, "p_amount", 25, "p_note", "Pela API")));
		assertTrue(error.getMessage().contains("Só superadmin"));
		assertEquals(25, balance(target));
	}
}
