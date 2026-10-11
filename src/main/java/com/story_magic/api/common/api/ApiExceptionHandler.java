package com.story_magic.api.common.api;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.story_magic.api.catalog.application.BookNotFoundException;
import com.story_magic.api.catalog.application.InvalidSearchQueryException;
import com.story_magic.api.integrations.ExternalServiceException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(BookNotFoundException.class)
	public ProblemDetail handleBookNotFound(BookNotFoundException exception) {
		return problem(HttpStatus.NOT_FOUND, "Livro não encontrado", exception.getMessage());
	}

	@ExceptionHandler(ExternalServiceException.class)
	public ProblemDetail handleExternalService(ExternalServiceException exception) {
		log.warn("External service request failed with status {}", exception.getStatus());
		return problem(exception.getStatus(), "Falha em serviço externo", exception.getMessage());
	}

	@ExceptionHandler(InvalidSearchQueryException.class)
	public ProblemDetail handleInvalidSearch(InvalidSearchQueryException exception) {
		return problem(HttpStatus.BAD_REQUEST, "Busca inválida", exception.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpectedError(Exception exception) {
		log.error("Unhandled API error", exception);
		return problem(
				HttpStatus.INTERNAL_SERVER_ERROR,
				"Erro interno",
				"Não foi possível concluir a solicitação.");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setProperty("timestamp", Instant.now());
		return problem;
	}
}
