package com.storyverse.api.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.io.IOException;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import io.sentry.Sentry;

/**
 * O fim normal de uma conexão SSE e o leitor que fecha a aba não são defeitos: antes viravam 500 e
 * issue no Sentry (STORYVERSE-API-5 e -6), com e-mail a cada nova versão.
 */
class ApiExceptionHandlerTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void streamTimeoutIsNotReportedToSentry() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			handler.handleStreamTimeout(new AsyncRequestTimeoutException());
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(0));
		}
	}

	@Test
	void brokenPipeWhileWritingIsNotReportedToSentry() {
		var brokenPipe = new HttpMessageNotWritableException(
				"Could not write JSON: ServletOutputStream failed to write",
				new IOException("Broken pipe"));
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			ResponseEntity<ApiError> response = handler.handleUnexpected(brokenPipe);
			assertNull(response, "cliente já foi embora: não há resposta para escrever");
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(0));
		}
	}

	@Test
	void clientAbortIsNotReportedToSentry() {
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			assertNull(handler.handleUnexpected(new RuntimeException(new ClientAbortException("aborted"))));
			sentry.verify(() -> Sentry.captureException(any(Throwable.class)), times(0));
		}
	}

	@Test
	void realSerializationErrorStillGoesToSentry() {
		var bug = new HttpMessageNotWritableException("Could not write JSON: infinite recursion");
		try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
			ResponseEntity<ApiError> response = handler.handleUnexpected(bug);
			assertNotNull(response);
			assertEquals(500, response.getStatusCode().value());
			sentry.verify(() -> Sentry.captureException(bug), times(1));
		}
	}
}
