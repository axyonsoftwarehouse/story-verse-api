package com.storyverse.api.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.storyverse.api.auth.AuthException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(AuthException.class)
	ResponseEntity<ApiError> handleAuth(AuthException exception) {
		return ResponseEntity.status(exception.status())
				.body(new ApiError(exception.getMessage(), exception.code()));
	}
}
