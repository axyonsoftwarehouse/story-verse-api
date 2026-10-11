package com.story_magic.api.integrations.openlibrary;

import java.util.List;

public record OpenLibraryBookDocument(
		String title,
		List<String> author_name,
		Integer first_publish_year,
		Integer cover_i,
		Integer edition_count,
		Editions editions) {

	public record Editions(List<Edition> docs) {
	}

	public record Edition(String title, List<String> language, Integer cover_i) {
	}
}
