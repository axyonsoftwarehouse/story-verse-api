package com.story_magic.api.catalog.application;

public class BookNotFoundException extends RuntimeException {

	public BookNotFoundException(String id) {
		super("Não existe livro no catálogo com o identificador: " + id);
	}
}
