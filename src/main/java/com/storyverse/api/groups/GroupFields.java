package com.storyverse.api.groups;

/** Campos editáveis de um grupo, já validados. {@code goalUntil} em ISO (yyyy-MM-dd). */
record GroupFields(
		String name,
		String description,
		String photoUrl,
		String background,
		String visibility,
		String bookKey,
		String bookTitle,
		String bookCover,
		Integer goalChapter,
		String goalUntil) {
}
