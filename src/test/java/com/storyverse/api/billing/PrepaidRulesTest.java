package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PrepaidRulesTest {

	private static final Instant AGORA = Instant.parse("2026-10-28T12:00:00Z");

	@Test
	void semHistoricoComecaAgora() {
		assertEquals(AGORA, PrepaidRules.startAt(AGORA, null, null));
	}

	@Test
	void pagarAntesDoFimSomaDias() {
		Instant fim = AGORA.plusSeconds(10 * 86_400L);
		assertEquals(fim, PrepaidRules.startAt(AGORA, fim, null));
	}

	@Test
	void periodoVencidoNaoAtrasaOInicio() {
		assertEquals(AGORA, PrepaidRules.startAt(AGORA, AGORA.minusSeconds(3600), null));
	}

	@Test
	void pixNoTesteGratisComecaQuandoOTesteAcaba() {
		Instant fimDoTeste = AGORA.plusSeconds(5 * 86_400L);
		assertEquals(fimDoTeste, PrepaidRules.startAt(AGORA, null,
				Map.of("status", "trialing", "trial_end", fimDoTeste.toString())));
	}

	@Test
	void cartaoCanceladoNoFimDoPeriodoComecaDepoisDele() {
		Instant fimDoCartao = AGORA.plusSeconds(20 * 86_400L);
		assertEquals(fimDoCartao, PrepaidRules.startAt(AGORA, null, Map.of(
				"status", "active", "cancel_at_period_end", true,
				"current_period_end", fimDoCartao.toString())));
	}

	@Test
	void cartaoCobrandoBloqueiaPix() {
		assertTrue(PrepaidRules.blocksPix(Map.of("status", "active", "cancel_at_period_end", false)));
		assertTrue(PrepaidRules.blocksPix(Map.of("status", "past_due")));
		assertFalse(PrepaidRules.blocksPix(Map.of("status", "active", "cancel_at_period_end", true)));
		assertFalse(PrepaidRules.blocksPix(Map.of("status", "canceled")));
		assertFalse(PrepaidRules.blocksPix(Map.of("status", "trialing")));
		assertFalse(PrepaidRules.blocksPix(null));
	}
}
