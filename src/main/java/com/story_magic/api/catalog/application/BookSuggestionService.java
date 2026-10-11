package com.story_magic.api.catalog.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.story_magic.api.catalog.domain.BookSuggestion;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument.Edition;
import com.story_magic.api.integrations.openlibrary.OpenLibrarySearchProvider;

@Service
public class BookSuggestionService {

	private static final int MIN_PUBLICATION_YEAR = 1930;
	private static final int MAX_RESULTS = 6;
	private static final int MAX_QUERY_LENGTH = 200;
	private static final String OPEN_LIBRARY_COVER_URL = "https://covers.openlibrary.org/b/id/";

	private final OpenLibrarySearchProvider openLibrary;

	public BookSuggestionService(OpenLibrarySearchProvider openLibrary) {
		this.openLibrary = openLibrary;
	}

	public List<BookSuggestion> search(String query) {
		String term = query == null ? "" : query.strip();
		if (term.isEmpty() || term.length() > MAX_QUERY_LENGTH) {
			throw new InvalidSearchQueryException("A busca deve ter entre 1 e 200 caracteres.");
		}

		List<String> queryWords = words(normalize(term));
		if (queryWords.isEmpty()) {
			throw new InvalidSearchQueryException("A busca deve conter ao menos uma letra ou número.");
		}

		List<OpenLibraryBookDocument> documents = openLibrary.search(term);
		boolean hasPortugueseEdition = documents.stream().anyMatch(BookSuggestionService::portugueseEdition);
		Set<String> seen = new HashSet<>();
		List<BookSuggestion> suggestions = new ArrayList<>();

		for (OpenLibraryBookDocument document : documents) {
			if (hasPortugueseEdition && !portugueseEdition(document)) {
				continue;
			}
			Edition edition = selectedEdition(document, hasPortugueseEdition);
			String title = selectedTitle(document, edition);
			String author = firstAuthor(document);
			String allAuthors = document.author_name() == null
					? ""
					: document.author_name().stream().filter(Objects::nonNull).collect(Collectors.joining(" "));
			Integer year = document.first_publish_year();
			Integer coverId = selectedCover(document, edition);

			if (title == null || author == null || year == null || year < MIN_PUBLICATION_YEAR
					|| document.edition_count() == null || document.edition_count() < 2 || coverId == null) {
				continue;
			}
			if (!matchesQuery(title, document.title(), allAuthors, queryWords)) {
				continue;
			}

			String key = normalize(title) + "|" + normalize(author);
			if (!seen.add(key)) {
				continue;
			}

			suggestions.add(new BookSuggestion(
					title,
					author,
					year,
					OPEN_LIBRARY_COVER_URL + coverId + "-M.jpg"));
			if (suggestions.size() == MAX_RESULTS) {
				break;
			}
		}
		return List.copyOf(suggestions);
	}

	private static boolean portugueseEdition(OpenLibraryBookDocument document) {
		return document.editions() != null && document.editions().docs() != null
				&& document.editions().docs().stream()
						.anyMatch(edition -> edition.language() != null && edition.language().contains("por"));
	}

	private static Edition selectedEdition(OpenLibraryBookDocument document, boolean preferPortuguese) {
		if (!preferPortuguese || document.editions() == null || document.editions().docs() == null) {
			return null;
		}
		return document.editions().docs().stream()
				.filter(edition -> edition.language() != null && edition.language().contains("por"))
				.findFirst()
				.orElse(null);
	}

	private static String selectedTitle(OpenLibraryBookDocument document, Edition edition) {
		if (edition != null && edition.title() != null && !edition.title().isBlank()) {
			return edition.title().strip();
		}
		return document.title() == null || document.title().isBlank() ? null : document.title().strip();
	}

	private static String firstAuthor(OpenLibraryBookDocument document) {
		if (document.author_name() == null || document.author_name().isEmpty()) {
			return null;
		}
		String author = document.author_name().getFirst();
		return author == null || author.isBlank() ? null : author.strip();
	}

	private static Integer selectedCover(OpenLibraryBookDocument document, Edition edition) {
		return edition != null && edition.cover_i() != null ? edition.cover_i() : document.cover_i();
	}

	private static boolean matchesQuery(String title, String originalTitle, String author, List<String> words) {
		String text = normalize(title + " " + (originalTitle == null ? "" : originalTitle) + " " + author);
		return words.stream().allMatch(text::contains);
	}

	private static List<String> words(String query) {
		return Arrays.stream(query.split("[^a-z0-9]+"))
				.filter(word -> !word.isEmpty())
				.toList();
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "");
	}
}
