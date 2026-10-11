package com.story_magic.api.catalog.application;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.story_magic.api.catalog.domain.BookSuggestion;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument.Edition;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument.Editions;
import com.story_magic.api.integrations.openlibrary.OpenLibrarySearchProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSuggestionServiceTests {

	@Mock
	private OpenLibrarySearchProvider openLibrary;

	private BookSuggestionService service;

	@BeforeEach
	void setUp() {
		service = new BookSuggestionService(openLibrary);
	}

	@Test
	void prefersPortugueseEditionTitleAndCoverAndOmitsEnglishOnlyResults() {
		OpenLibraryBookDocument portuguese = document(
				"Twilight", List.of("Stephenie Meyer"), 2005, 10, 20,
				new Editions(List.of(
						new Edition("Crepúsculo", List.of("por"), 54321),
						new Edition("Twilight", List.of("eng"), 54322))));
		OpenLibraryBookDocument englishOnly = document(
				"Twilight Companion", List.of("Stephenie Meyer"), 2006, 123, 8,
				new Editions(List.of(new Edition("Twilight Companion", List.of("eng"), 123))));
		when(openLibrary.search("crepusculo")).thenReturn(List.of(portuguese, englishOnly));

		assertEquals(List.of(new BookSuggestion(
				"Crepúsculo", "Stephenie Meyer", 2005, "https://covers.openlibrary.org/b/id/54321-M.jpg")),
				service.search("crepusculo"));
	}

	@Test
	void fallsBackToDocumentCoverWhenPreferredEditionHasNoCover() {
		OpenLibraryBookDocument document = document(
				"Amor de Perdição", List.of("Camilo Castelo Branco"), 2000, 456, 5,
				new Editions(List.of(new Edition("Amor de Perdição", List.of("por"), null))));
		when(openLibrary.search("amor")).thenReturn(List.of(document));

		assertEquals("https://covers.openlibrary.org/b/id/456-M.jpg",
				service.search("amor").getFirst().coverUrl());
	}

	@Test
	void filtersOutIncompleteOldAndIrrelevantDocuments() {
		when(openLibrary.search("aventura")).thenReturn(List.of(
				document("Aventura Antiga", List.of("Autor"), 1929, 1, 4, null),
				document("Aventura Única", List.of("Autor"), 2000, 2, 1, null),
				document("Aventura sem capa", List.of("Autor"), 2000, null, 4, null),
				document("Aventura sem autor", List.of(), 2000, 2, 4, null),
				document("Outro título", List.of("Autor"), 2000, 2, 4, null)));

		assertEquals(List.of(), service.search("aventura"));
	}

	@Test
	void normalizesAccentsAndDeduplicatesByTitleAndAuthor() {
		when(openLibrary.search("crepusculo")).thenReturn(List.of(
				document("Crepúsculo", List.of("Stephenie Meyer"), 2005, 1, 20, null),
				document("CREPUSCULO", List.of("Stephenie Meyer"), 2006, 2, 21, null)));

		List<BookSuggestion> suggestions = service.search("crepusculo");

		assertEquals(1, suggestions.size());
		assertEquals(2005, suggestions.getFirst().year());
	}

	@Test
	void limitsResultsToSix() {
		List<OpenLibraryBookDocument> documents = new ArrayList<>();
		for (int index = 0; index < 8; index++) {
			documents.add(document("Aventura livro " + index, List.of("Autor " + index),
					2000 + index, 100 + index, 3, null));
		}
		when(openLibrary.search("aventura")).thenReturn(documents);

		assertEquals(6, service.search("aventura").size());
	}

	@Test
	void rejectsBlankOverlongAndNonAlphanumericQueriesBeforeCallingProvider() {
		assertThrows(InvalidSearchQueryException.class, () -> service.search(" \t "));
		assertThrows(InvalidSearchQueryException.class, () -> service.search("x".repeat(201)));
		assertThrows(InvalidSearchQueryException.class, () -> service.search("!?---"));
		verify(openLibrary, never()).search(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void preservesShortAlphanumericSearchTerms() {
		when(openLibrary.search("AI")).thenReturn(List.of(
				document("AI systems", List.of("Researcher"), 2020, 22, 2, null)));

		assertEquals(1, service.search("AI").size());
	}

	private static OpenLibraryBookDocument document(
			String title,
			List<String> authors,
			Integer year,
			Integer coverId,
			Integer editionCount,
			Editions editions) {
		return new OpenLibraryBookDocument(title, authors, year, coverId, editionCount, editions);
	}
}
