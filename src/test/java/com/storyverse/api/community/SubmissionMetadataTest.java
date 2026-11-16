package com.storyverse.api.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SubmissionMetadataTest {

	@Test
	void acceptsRealTitleAndAuthor() {
		assertNull(SubmissionMetadata.problem("Dom Casmurro", "Machado de Assis"));
		assertNull(SubmissionMetadata.problem("O Hobbit", "J. R. R. Tolkien"));
		assertNull(SubmissionMetadata.problem("1984", "George Orwell"));
	}

	@Test
	void rejectsFileNamesSeenInProduction() {
		assertNotNull(SubmissionMetadata.problem("Morte_DiaValePenaViver_FINAL.indd", "Alguém"));
		assertNotNull(SubmissionMetadata.problem("FLORES, Coiote QueDeusTeriaPiedadeDeNos[2025]", "Coiote Flores"));
		assertNotNull(SubmissionMetadata.problem("meu livro.PDF", "Fulano"));
		assertNotNull(SubmissionMetadata.problem("capitulo.epub", "Fulano"));
	}

	@Test
	void requiresTitle() {
		assertEquals("Informe o título do livro.", SubmissionMetadata.problem(null, "Fulano"));
		assertEquals("Informe o título do livro.", SubmissionMetadata.problem("   ", "Fulano"));
	}

	@Test
	void requiresAuthorThatIdentifiesSomeone() {
		assertEquals("Informe o autor do livro.", SubmissionMetadata.problem("Mulheres que correm com os lobos", "V"));
		assertEquals("Informe o autor do livro.", SubmissionMetadata.problem("Livro", null));
		assertEquals("Informe o autor do livro.", SubmissionMetadata.problem("Livro", "Autor não informado"));
		assertEquals("Informe o autor do livro.", SubmissionMetadata.problem("Livro", " ? "));
	}
}
