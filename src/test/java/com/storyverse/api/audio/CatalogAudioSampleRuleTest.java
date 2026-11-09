package com.storyverse.api.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S4 da revisão de 09/11/2026: qual capítulo sai sem login. */
class CatalogAudioSampleRuleTest {

	@Test
	void primeiroCapituloCurtoDeEdicaoComVariosCapitulosEAmostra() {
		assertTrue(CatalogAudioService.freeSample(1, 12, 60));
		assertTrue(CatalogAudioService.freeSample(1, 2, CatalogAudioService.SAMPLE_MAX_SECONDS));
	}

	@Test
	void capituloUnicoNuncaEAmostra() {
		assertFalse(CatalogAudioService.freeSample(1, 1, 60));
		assertFalse(CatalogAudioService.freeSample(1, 1, 25_916));
	}

	@Test
	void primeiroCapituloLongoOuSemDuracaoNaoEAmostra() {
		assertFalse(CatalogAudioService.freeSample(1, 12, CatalogAudioService.SAMPLE_MAX_SECONDS + 1));
		assertFalse(CatalogAudioService.freeSample(1, 12, null));
		assertFalse(CatalogAudioService.freeSample(1, 12, 0));
		assertFalse(CatalogAudioService.freeSample(1, null, 60));
	}

	@Test
	void demaisCapitulosNuncaSaoAmostra() {
		assertFalse(CatalogAudioService.freeSample(2, 12, 60));
	}
}
