package com.story_magic.api.catalog.application;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.story_magic.api.catalog.domain.Book;
import com.story_magic.api.integrations.gutenberg.BookContentProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceTests {

	@Mock
	private BookCatalogRepository repository;

	@Mock
	private BookContentProvider contentProvider;

	private BookService service;

	@BeforeEach
	void setUp() {
		service = new BookService(repository, contentProvider);
	}

	@Test
	void nullOrBlankQueryReturnsTheCompleteCatalog() {
		List<Book> books = List.of(book("gb-1", 1, "Iracema"), book("gb-2", 2, "Drácula"));
		when(repository.findAll()).thenReturn(books);

		assertSame(books, service.search(null));
		assertSame(books, service.search(" \t "));
		verify(repository, org.mockito.Mockito.times(2)).findAll();
	}

	@Test
	void searchMatchesEverySupportedFieldWithoutCaseSensitivity() {
		Book book = new Book("gb-1", 1, "Aventura", "Autor", "Fantasia", "Busca na sinopse",
				"https://example.test/cover.jpg", "pt", "fantasia");
		when(repository.findAll()).thenReturn(List.of(book));

		assertEquals(List.of(book), service.search(" AVENTURA "));
		assertEquals(List.of(book), service.search("autor"));
		assertEquals(List.of(book), service.search("FANTASIA"));
		assertEquals(List.of(book), service.search("SINOPSE"));
		assertEquals(List.of(), service.search("ausente"));
	}

	@Test
	void findByIdDelegatesToTheCatalogRepository() {
		Book book = book("gb-1", 1, "Iracema");
		when(repository.findById("gb-1")).thenReturn(Optional.of(book));

		assertEquals(Optional.of(book), service.findById("gb-1"));
		verify(repository).findById("gb-1");
	}

	@Test
	void contentIsFetchedUsingTheCatalogGutenbergId() {
		Book book = book("gb-67740", 67740, "Iracema");
		when(repository.findById(book.id())).thenReturn(Optional.of(book));
		when(contentProvider.fetchPlainText(67740)).thenReturn("Conteúdo");

		assertEquals("Conteúdo", service.getPlainText(book.id()));
		verify(contentProvider).fetchPlainText(67740);
	}

	@Test
	void contentForUnknownBookDoesNotCallTheExternalProvider() {
		when(repository.findById("gb-999")).thenReturn(Optional.empty());

		assertThrows(BookNotFoundException.class, () -> service.getPlainText("gb-999"));
		verify(contentProvider, never()).fetchPlainText(org.mockito.ArgumentMatchers.anyInt());
	}

	private static Book book(String id, int gutenbergId, String title) {
		return new Book(id, gutenbergId, title, "José de Alencar", "Romance", "Sinopse",
				"https://example.test/cover.jpg", "pt", "romance");
	}
}
