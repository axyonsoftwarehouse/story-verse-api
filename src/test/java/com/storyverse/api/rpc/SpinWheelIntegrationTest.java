package com.storyverse.api.rpc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

/** Roleta diária (V48): prêmios de 5 a 100 e um giro por dia. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class SpinWheelIntegrationTest {

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.auth.require-email-confirmation", () -> "false");
		properties.add("app.security.require-runtime-role", () -> "false");
	}

	/** Os mesmos valores das fatias do app (SPIN_SEGMENTS no story-verse-web). */
	static final Set<Integer> PRIZES = Set.of(5, 10, 15, 25, 50, 100);

	@Autowired JdbcClient jdbc;
	@Autowired PlatformTransactionManager transactions;

	UUID newUser() {
		return jdbc.sql("insert into auth.users (email) values (:e) returning id")
				.param("e", UUID.randomUUID() + "@example.invalid").query(UUID.class).single();
	}

	/** Gira como a conta, pelo papel de runtime da VPS (a RLS vale). */
	Map<String, Object> spin(UUID user) {
		return new TransactionTemplate(transactions).execute(status -> {
			jdbc.sql("set local role storyverse_api").update();
			DbSession.setUser(jdbc, user);
			return DbRows.one(jdbc, "select * from public.spin_daily_wheel()", Map.of()).orElseThrow();
		});
	}

	@Test
	void prizesAreTheNewLadderAndOnlyOncePerDay() {
		UUID user = newUser();
		Map<String, Object> first = spin(user);
		int prize = ((Number) first.get("prize")).intValue();
		assertTrue(PRIZES.contains(prize), "prêmio fora da roleta: " + prize);
		assertEquals(Boolean.TRUE, first.get("is_new"));
		assertEquals(prize, ((Number) first.get("balance")).intValue());

		Map<String, Object> again = spin(user);
		assertEquals(Boolean.FALSE, again.get("is_new"), "segundo giro no mesmo dia não ganha de novo");
		assertEquals(prize, ((Number) again.get("prize")).intValue());
		assertEquals(prize, ((Number) again.get("balance")).intValue());
	}

	@Test
	void manySpinsStayInsideTheWheel() {
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < 120; i++) {
			int prize = ((Number) spin(newUser()).get("prize")).intValue();
			assertTrue(PRIZES.contains(prize), "prêmio fora da roleta: " + prize);
			seen.add(prize);
		}
		// 5, 10 e 15 somam 81%: em 120 giros os três aparecem (chance de falhar é desprezível).
		assertTrue(seen.containsAll(Set.of(5, 10, 15)), "faixas comuns não apareceram: " + seen);
	}
}
