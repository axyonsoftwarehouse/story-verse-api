package com.story_magic.api.integrations;

import org.springframework.http.HttpStatus;

public class ExternalServiceException extends RuntimeException {

	private final HttpStatus status;

	public ExternalServiceException(HttpStatus status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public HttpStatus getStatus() {
		return status;
	}
}
