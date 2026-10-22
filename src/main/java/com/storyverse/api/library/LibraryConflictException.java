package com.storyverse.api.library;

/** A entrada mudou desde o `baseUpdatedAt` do cliente; leva a versão atual para o merge (409). */
public class LibraryConflictException extends RuntimeException {

	private final transient LibraryEntry current;

	public LibraryConflictException(LibraryEntry current) {
		super("A entrada mudou em outro aparelho.");
		this.current = current;
	}

	public LibraryEntry current() {
		return current;
	}
}
