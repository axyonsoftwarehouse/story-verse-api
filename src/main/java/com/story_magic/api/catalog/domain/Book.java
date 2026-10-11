package com.story_magic.api.catalog.domain;

public record Book(
		String id,
		int gutenbergId,
		String title,
		String author,
		String genre,
		String blurb,
		String coverUrl,
		String textLanguage,
		String shelf) {
}
