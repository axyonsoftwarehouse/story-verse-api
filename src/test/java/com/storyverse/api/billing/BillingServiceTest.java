package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Transactional;

class BillingServiceTest {

	@Test
	void activeValeAteOFimDoPeriodo() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "active",
				"current_period_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "active",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void trialValeAteOTrialEnd() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "trialing",
				"trial_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "trialing",
				"trial_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void pastDueTemCarenciaDeTresDias() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "past_due",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "past_due",
				"current_period_end", Instant.now().minusSeconds(4 * 86_400L).toString()), 3).active());
	}

	@Test
	void canceledValeAteOFimDoPeriodo() {
		assertTrue(BillingService.evaluate(Map.of(
				"status", "canceled",
				"current_period_end", Instant.now().plusSeconds(3600).toString()), 3).active());
		assertFalse(BillingService.evaluate(Map.of(
				"status", "canceled",
				"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
	}

	@Test
	void unpaidEIncompleteNaoLiberam() {
		assertFalse(BillingService.evaluate(Map.of("status", "unpaid"), 3).active());
		assertFalse(BillingService.evaluate(Map.of("status", "incomplete"), 3).active());
		assertFalse(BillingService.evaluate(Map.of("status", "paused"), 3).active());
	}

	/**
	 * requireAccess chama access() por auto-invocação, então o @Transactional dele não vale:
	 * sem transação própria o set_config(..., true) do RLS se perde e até quem tem plano leva 402.
	 * Este teste trava a anotação para a regressão não voltar silenciosa.
	 */
	@Test
	void requireAccessPrecisaDeTransacaoPropria() throws Exception {
		Transactional anotacao = AnnotationUtils.findAnnotation(
				BillingService.class.getMethod("requireAccess", UUID.class), Transactional.class);
		assertNotNull(anotacao, "requireAccess precisa de @Transactional para o RLS enxergar a assinatura");
	}

	@Nested
	class Trialing {

		@Test
		void ativoAteOTrialEnd() {
			BillingService.Access acesso = BillingService.evaluate(Map.of(
					"status", "trialing",
					"trial_end", Instant.now().plusSeconds(3600).toString()), 3);
			assertTrue(acesso.active());
			assertEquals("trial", acesso.reason());
			assertNotNull(acesso.accessUntil());
		}

		@Test
		void inativoDepoisDoTrialEnd() {
			assertFalse(BillingService.evaluate(Map.of(
					"status", "trialing",
					"trial_end", Instant.now().minusSeconds(3600).toString()), 3).active());
		}

		@Test
		void semTrialEndUsaCurrentPeriodEnd() {
			// Sem trial_end, o código usa current_period_end como fallback.
			assertTrue(BillingService.evaluate(Map.of(
					"status", "trialing",
					"current_period_end", Instant.now().plusSeconds(3600).toString()), 3).active());
			assertFalse(BillingService.evaluate(Map.of(
					"status", "trialing",
					"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
		}

		/**
		 * Comportamento estranho: sem trial_end E sem current_period_end, `until` é null e o
		 * acesso fica ativo (a condição é `until == null || now.isBefore(until)`).
		 */
		@Test
		void semNenhumaDataFicaAtivo() {
			BillingService.Access acesso = BillingService.evaluate(Map.of("status", "trialing"), 3);
			assertTrue(acesso.active());
			assertEquals("trial", acesso.reason());
			assertNull(acesso.accessUntil());
		}
	}

	@Nested
	class Active {

		@Test
		void ativoAteOFimDoPeriodo() {
			BillingService.Access acesso = BillingService.evaluate(Map.of(
					"status", "active",
					"current_period_end", Instant.now().plusSeconds(3600).toString()), 3);
			assertTrue(acesso.active());
			assertEquals("active", acesso.reason());
			assertNotNull(acesso.accessUntil());
		}

		/**
		 * Comportamento estranho (contra o esperado na tarefa): `active` NÃO aplica graceDays.
		 * Passou do current_period_end, fica inativo mesmo dentro da carência.
		 */
		@Test
		void foraDoPeriodoNaoTemCarencia() {
			assertFalse(BillingService.evaluate(Map.of(
					"status", "active",
					"current_period_end", Instant.now().minusSeconds(3600).toString()), 3).active());
			assertFalse(BillingService.evaluate(Map.of(
					"status", "active",
					"current_period_end", Instant.now().minusSeconds(4 * 86_400L).toString()), 3).active());
		}

		/**
		 * Comportamento estranho: `active` sem current_period_end fica ativo para sempre
		 * (a condição é `periodEnd == null || now.isBefore(periodEnd)`).
		 */
		@Test
		void semPeriodEndFicaAtivo() {
			BillingService.Access acesso = BillingService.evaluate(Map.of("status", "active"), 3);
			assertTrue(acesso.active());
			assertEquals("active", acesso.reason());
			assertNull(acesso.accessUntil());
		}
	}

	@Nested
	class PastDue {

		@Test
		void dentroDaCarencia() {
			BillingService.Access acesso = BillingService.evaluate(Map.of(
					"status", "past_due",
					"current_period_end", Instant.now().minusSeconds(3600).toString()), 3);
			assertTrue(acesso.active());
			assertEquals("past_due", acesso.reason());
			assertNotNull(acesso.accessUntil());
		}

		@Test
		void foraDaCarencia() {
			assertFalse(BillingService.evaluate(Map.of(
					"status", "past_due",
					"current_period_end", Instant.now().minusSeconds(4 * 86_400L).toString()), 3).active());
		}

		@Test
		void semPeriodEndFicaInativo() {
			BillingService.Access acesso = BillingService.evaluate(Map.of("status", "past_due"), 3);
			assertFalse(acesso.active());
			assertEquals("past_due", acesso.reason());
		}

		@Test
		void carenciaZeroInativaImediatamente() {
			assertFalse(BillingService.evaluate(Map.of(
					"status", "past_due",
					"current_period_end", Instant.now().minusSeconds(1).toString()), 0).active());
			assertTrue(BillingService.evaluate(Map.of(
					"status", "past_due",
					"current_period_end", Instant.now().plusSeconds(3600).toString()), 0).active());
		}
	}

	@Nested
	class StatusTerminais {

		@Test
		void canceledInativoSemPeriodo() {
			BillingService.Access acesso = BillingService.evaluate(Map.of("status", "canceled"), 3);
			assertFalse(acesso.active());
			assertEquals("canceled", acesso.reason());
		}

		@Test
		void unpaidIncompleteEIncompleteExpiredNaoLiberam() {
			assertFalse(BillingService.evaluate(Map.of("status", "unpaid"), 3).active());
			assertFalse(BillingService.evaluate(Map.of("status", "incomplete"), 3).active());
			assertFalse(BillingService.evaluate(Map.of("status", "incomplete_expired"), 3).active());
		}

		@Test
		void reasonRefleteOStatus() {
			assertEquals("unpaid", BillingService.evaluate(Map.of("status", "unpaid"), 3).reason());
			assertEquals("incomplete", BillingService.evaluate(Map.of("status", "incomplete"), 3).reason());
			assertEquals("incomplete_expired", BillingService.evaluate(Map.of("status", "incomplete_expired"), 3).reason());
		}
	}

	@Nested
	class StatusDesconhecido {

		@Test
		void statusWeirdFicaInativoSemExcecao() {
			BillingService.Access acesso = BillingService.evaluate(Map.of("status", "weird"), 3);
			assertFalse(acesso.active());
			assertEquals("weird", acesso.reason());
			assertNull(acesso.accessUntil());
		}

		@Test
		void statusNuloViraStringNull() {
			// String.valueOf(null) vira "null": cai no default, inativo com reason "null".
			BillingService.Access acesso = BillingService.evaluate(assinatura("status", null), 3);
			assertFalse(acesso.active());
			assertEquals("null", acesso.reason());
		}
	}

	@Nested
	class DatasInvalidas {

		/**
		 * instant() converte null, texto inválido e tipos não suportados em null — sem lançar.
		 * O efeito depende do status: active/trialing ficam ativos; past_due/canceled, inativos.
		 */
		@Test
		void textoInvalidoNaoLanca() {
			assertFalse(BillingService.evaluate(assinatura(
					"status", "past_due",
					"current_period_end", "não-é-data"), 3).active());
		}

		@Test
		void tipoNaoSuportadoNaoLanca() {
			assertFalse(BillingService.evaluate(assinatura(
					"status", "past_due",
					"current_period_end", 12345), 3).active());
		}

		@Test
		void dataNulaDeixaActiveAtivo() {
			assertTrue(BillingService.evaluate(assinatura(
					"status", "active",
					"current_period_end", null), 3).active());
		}

		@Test
		void instantObjetoFunciona() {
			assertTrue(BillingService.evaluate(Map.of(
					"status", "active",
					"current_period_end", Instant.now().plusSeconds(3600)), 3).active());
		}
	}

	private static Map<String, Object> assinatura(Object... pares) {
		Map<String, Object> map = new java.util.HashMap<>();
		for (int i = 0; i < pares.length; i += 2) {
			map.put((String) pares[i], pares[i + 1]);
		}
		return map;
	}
}
