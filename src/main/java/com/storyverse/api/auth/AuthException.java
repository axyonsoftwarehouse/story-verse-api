package com.storyverse.api.auth;

public class AuthException extends RuntimeException {

	private final int status;
	private final String code;

	public AuthException(int status, String message) {
		this(status, message, null);
	}

	public AuthException(int status, String message, String code) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}
}
