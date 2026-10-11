package com.story_magic.api.catalog.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.story_magic.api.catalog.domain.Book;
import com.story_magic.api.integrations.gutenberg.BookContentProvider;

@Service
public class BookService {

	private final BookCatalogRepository repository;
	private final BookContentProvider contentProvider;

	public BookService(BookCatalogRepository repository, BookContentProvider contentProvider) {
		this.repository = repository;
		this.contentProvider = contentProvider;
	}

	public List<Book> search(String query) {
		if (query == null || query.isBlank()) {
			return repository.findAll();
		}

		String normalizedQuery = query.strip().toLowerCase(Locale.ROOT);
		return repository.findAll().stream()
				.filter(book -> contains(book.title(), normalizedQuery)
						|| contains(book.author(), normalizedQuery)
						|| contains(book.genre(), normalizedQuery)
						|| contains(book.blurb(), normalizedQuery))
				.toList();
	}

	public Optional<Book> findById(String id) {
		return repository.findById(id);
	}

	public String getPlainText(String id) {
		Book book = repository.findById(id)
				.orElseThrow(() -> new BookNotFoundException(id));
		return contentProvider.fetchPlainText(book.gutenbergId());
	}

	private static boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query);
	}
}
