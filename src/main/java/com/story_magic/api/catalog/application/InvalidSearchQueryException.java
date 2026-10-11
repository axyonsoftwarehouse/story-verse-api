package com.story_magic.api.catalog.application;

public class InvalidSearchQueryException extends RuntimeException {

	public InvalidSearchQueryException(String message) {
		super(message);
	}
}
