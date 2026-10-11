package com.story_magic.api.catalog.api;

import com.story_magic.api.catalog.domain.BookSuggestion;

public record BookSuggestionResponse(String title, String author, int year, String coverUrl) {

	public static BookSuggestionResponse from(BookSuggestion suggestion) {
		return new BookSuggestionResponse(
				suggestion.title(),
				suggestion.author(),
				suggestion.year(),
				suggestion.coverUrl());
	}
}
