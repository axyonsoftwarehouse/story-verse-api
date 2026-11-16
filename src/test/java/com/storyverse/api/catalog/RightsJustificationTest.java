package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RightsJustificationTest {

	@Test
	void exigeTextoComFonteOuMotivo() {
		assertEquals(RightsJustification.MESSAGE, RightsJustification.problem(null));
		assertEquals(RightsJustification.MESSAGE, RightsJustification.problem("   "));
		assertEquals(RightsJustification.MESSAGE, RightsJustification.problem("ok, pode"));
		assertNull(RightsJustification.problem("Gutenberg; autor morreu em 1910"));
		assertNull(RightsJustification.problem("  CC BY 4.0, site da autora  "));
	}
}
