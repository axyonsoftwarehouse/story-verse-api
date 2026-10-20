package com.storyverse.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import com.storyverse.api.auth.AuthException;

import io.sentry.Sentry;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(AuthException.class)
	ResponseEntity<ApiError> handleAuth(AuthException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ApiError(exception.getMessage(), exception.code()));
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	void handleClientDisconnect() {
		// Cliente SSE desconectou: a resposta já foi encerrada e não há corpo para escrever.
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception exception) {
		Sentry.captureException(exception);
		log.error("Erro não tratado", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiError("Erro interno."));
	}
}
