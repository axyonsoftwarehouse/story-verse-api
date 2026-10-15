package com.storyverse.api.ai;

class AiHttpException extends RuntimeException {

	final int status;
	final Long retryAfterMs;

	AiHttpException(int status, Long retryAfterMs) {
		super("IA HTTP " + status);
		this.status = status;
		this.retryAfterMs = retryAfterMs;
	}
}
