package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.storyverse.api.auth.AuthException;

class CatalogImportCsvTest {

	@Test
	void leCabecalhoEAspasComVirgulaEQuebraDeLinha() {
		String csv = "file,title,rights,description\n"
				+ "livro.txt,\"Dom, Casmurro\",public_domain,\"Primeira linha\nSegunda linha\"\n";
		List<Map<String, String>> rows = CatalogImportCsv.parse(csv);

		assertEquals(1, rows.size());
		assertEquals("livro.txt", rows.get(0).get("file"));
		assertEquals("Dom, Casmurro", rows.get(0).get("title"));
		assertEquals("Primeira linha\nSegunda linha", rows.get(0).get("description"));
		assertEquals("2", rows.get(0).get("_line"));
	}

	@Test
	void aceitaAspasEscapadasEBom() {
		String csv = "\uFEFFfile,title,rights\nlivro.md,\"Diga \"\"oi\"\"\",own_work\n";
		List<Map<String, String>> rows = CatalogImportCsv.parse(csv);

		assertEquals("Diga \"oi\"", rows.get(0).get("title"));
	}

	@Test
	void exigeAsColunasObrigatorias() {
		AuthException error = assertThrows(AuthException.class,
				() -> CatalogImportCsv.parse("file,title\nlivro.txt,Livro\n"));
		assertTrue(error.getMessage().contains("rights"));
	}

	@Test
	void tituloPodeVirDoArquivo() {
		List<Map<String, String>> rows = CatalogImportCsv.parse("file,rights\nlivro.epub,public_domain\n");
		assertEquals("livro.epub", rows.get(0).get("file"));
		assertEquals("public_domain", rows.get(0).get("rights"));
	}

	@Test
	void recusaAspasNaoFechadas() {
		assertThrows(AuthException.class,
				() -> CatalogImportCsv.parse("file,title,rights\n\"livro.txt,Livro,public_domain\n"));
	}
}
