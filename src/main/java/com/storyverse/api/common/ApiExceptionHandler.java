package com.storyverse.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.storyverse.api.admin.AccountSanctionedException;
import com.storyverse.api.admin.Sanction;
import com.storyverse.api.admin.SanctionError;
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

	@ExceptionHandler(AccountSanctionedException.class)
	ResponseEntity<SanctionError> handleSanction(AccountSanctionedException exception) {
		Sanction sanction = exception.sanction();
		String message = "ban".equals(sanction.kind()) ? "Conta banida." : "Conta suspensa.";
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new SanctionError(message, "ACCOUNT_SANCTIONED", sanction.kind(),
						sanction.reason(), sanction.expiresAt()));
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	void handleClientDisconnect() {
		// Cliente SSE desconectou: a resposta jǭ foi encerrada e nǜo hǭ corpo para escrever.
	}

	/**
	 * Conexão SSE (grupos, presença) chegou ao fim da vida: o cliente reconecta. Não é erro; antes
	 * caía no tratador genérico, respondia 500 e abria issue no Sentry a cada 5 minutos por leitor.
	 */
	@ExceptionHandler(AsyncRequestTimeoutException.class)
	void handleStreamTimeout(AsyncRequestTimeoutException exception) {
		// A resposta do stream já foi enviada; não há corpo para escrever.
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNotFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ApiError("Recurso não encontrado.", "NOT_FOUND"));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception exception) {
		// Leitor fechou a aba ou perdeu a rede no meio da resposta ("Broken pipe"): nada a corrigir
		// aqui, e o cliente nem receberia o 500. Erro de verdade (ex.: JSON que não serializa) segue.
		if (clientGone(exception)) return null;
		Sentry.captureException(exception);
		log.error("Erro não tratado", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ApiError("Erro interno."));
	}

	/** Algum elo da cadeia diz que o cliente desconectou enquanto a resposta era escrita. */
	static boolean clientGone(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof ClientAbortException || cause instanceof AsyncRequestNotUsableException) return true;
			if (cause instanceof IOException && cause.getMessage() != null) {
				String message = cause.getMessage().toLowerCase();
				if (message.contains("broken pipe") || message.contains("connection reset")
						|| message.contains("connection was aborted")) return true;
			}
			if (cause.getCause() == cause) break;
		}
		return false;
	}
}
