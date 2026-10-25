package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

/**
 * O fechamento do evento de webhook. Rode só contra um PostgreSQL descartável.
 *
 * <p>Regressão: {@code finishEvent(id, null)} mandava o parâmetro nulo sem tipo e o Postgres
 * recusava o update inteiro ("could not determine data type of parameter $1"). Como o
 * {@code finishEvent} também roda no caminho de sucesso, <b>toda</b> entrega de webhook devolvia
 * 500 e nada era gravado — nem a assinatura, nem o registro do evento.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STORYVERSE_TEST_DB_URL", matches = ".+")
class BillingWebhookIntegrationTest {

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
		properties.add("spring.datasource.username", () -> "postgres");
		properties.add("spring.datasource.password", () -> "local-audit-test-only");
		properties.add("spring.flyway.enabled", () -> "true");
		properties.add("app.auth.require-email-confirmation", () -> "false");
		// O ambiente de CI não tem o papel de runtime; o guard é validado no Compose/VPS.
		properties.add("app.security.require-runtime-role", () -> "false");
		properties.add("app.public-api-url", () -> "https://api.example.test");
		properties.add("app.rate-limit.persistent", () -> "true");
		properties.add("app.storage.dir", () -> System.getProperty("java.io.tmpdir"));
	}

	@Autowired
	BillingRepository repository;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager transactions;

	record Linha(String processado, String erro, int tentativas) {}

	private Linha evento(String id) {
		return jdbc.sql("""
				select processed_at, error, attempts from public.billing_events
				 where stripe_event_id = :id
				""").param("id", id)
				.query((rs, row) -> new Linha(
						rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toString(),
						rs.getString("error"),
						rs.getInt("attempts")))
				.single();
	}

	@Test
	void fechaEventoComSucessoGravandoProcessedAt() {
		String id = "evt_test_ok_" + UUID.randomUUID();
		repository.beginEvent(id, "customer.subscription.created");

		repository.finishEvent(id, null);

		Linha linha = evento(id);
		assertNotNull(linha.processado(), "processed_at precisa ser preenchido: era aqui que estourava");
		assertNull(linha.erro());
	}

	@Test
	void eventoComFalhaGuardaAMensagemESegueSemProcessar() {
		String id = "evt_test_erro_" + UUID.randomUUID();
		repository.beginEvent(id, "invoice.paid");

		repository.finishEvent(id, "Cliente do Stripe sem conta vinculada.");

		Linha linha = evento(id);
		assertNull(linha.processado(), "com erro o evento continua pendente de retentativa");
		assertEquals("Cliente do Stripe sem conta vinculada.", linha.erro());
	}

	@Test
	void segundaEntregaDoMesmoEventoContaTentativaEReconheceOProcessado() {
		String id = "evt_test_dup_" + UUID.randomUUID();
		repository.beginEvent(id, "checkout.session.completed");
		repository.finishEvent(id, null);

		repository.beginEvent(id, "checkout.session.completed");

		assertEquals(2, evento(id).tentativas(), "a segunda entrega soma tentativa em vez de duplicar a linha");
		assertNotNull(evento(id).processado());
	}

	/** Fluxo real: beginEvent e finishEvent dentro de uma transação externa (a do handle). */
	@Test
	void eventoFechadoDentroDaTransacaoDoHandleFicaProcessado() {
		String id = "evt_test_handle_" + UUID.randomUUID();
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			repository.beginEvent(id, "customer.subscription.created");
			repository.finishEvent(id, null);
		});

		assertNotNull(evento(id).processado(), "o update do finishEvent precisa enxergar a linha do beginEvent");
	}

	/** Se a sincronização falhar, o registro do evento continua (é a trilha de auditoria). */
	@Test
	void eventoContinuaRegistradoQuandoASincronizacaoFalha() {
		String id = "evt_test_rollback_" + UUID.randomUUID();
		try {
			new TransactionTemplate(transactions).executeWithoutResult(status -> {
				repository.beginEvent(id, "customer.subscription.created");
				throw new IllegalStateException("falha ao sincronizar");
			});
		} catch (IllegalStateException esperado) {
			// a transação externa reverte; o evento já foi commitado em transação própria
		}
		// finishEvent roda em transação nova, como o handler faz no catch
		repository.finishEvent(id, "falha ao sincronizar");

		Linha linha = evento(id);
		assertNull(linha.processado(), "com erro o evento fica pendente");
		assertEquals("falha ao sincronizar", linha.erro());
	}
}
