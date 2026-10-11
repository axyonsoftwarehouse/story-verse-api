package com.story_magic.api.catalog.api;

import com.story_magic.api.catalog.domain.Book;

public record BookResponse(
		String id,
		int gutenbergId,
		String title,
		String author,
		String genre,
		String blurb,
		String coverUrl,
		String textLanguage,
		String shelf) {

	public static BookResponse from(Book book) {
		return new BookResponse(
				book.id(),
				book.gutenbergId(),
				book.title(),
				book.author(),
				book.genre(),
				book.blurb(),
				book.coverUrl(),
				book.textLanguage(),
				book.shelf());
	}
}
