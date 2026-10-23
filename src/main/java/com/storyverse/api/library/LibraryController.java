package com.storyverse.api.library;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthTokens;
import com.storyverse.api.common.ApiError;

import tools.jackson.databind.JsonNode;

/** Estante da conta. Exige a feature `library` (403 FEATURE_DISABLED sem ela). */
@RestController
@RequestMapping("/api/v1/me/library")
public class LibraryController {

	private final LibraryService service;

	public LibraryController(LibraryService service) {
		this.service = service;
	}

	@GetMapping
	public LibraryPage list(@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestParam(required = false) List<String> status,
			@RequestParam(required = false) String author,
			@RequestParam(required = false) String series,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) String genre,
			@RequestParam(required = false) String year,
			@RequestParam(required = false) String minRating,
			@RequestParam(required = false) String maxRating,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String limit,
			@RequestParam(required = false) String updatedSince) {
		return service.list(AuthTokens.bearer(authorization), new LibraryQuery(status, author, series, language, genre,
				integer(year, "Ano inválido."), decimal(minRating), decimal(maxRating), sort, cursor,
				integer(limit, "Limite inválido."), updatedSince));
	}

	@GetMapping("/stats")
	public LibraryStats stats(@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestParam(required = false) String year) {
		return service.stats(AuthTokens.bearer(authorization), integer(year, "Ano inválido."));
	}

	@GetMapping("/{bookKey}")
	public LibraryEntry get(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey) {
		return service.get(AuthTokens.bearer(authorization), bookKey);
	}

	@PutMapping("/{bookKey}")
	public LibraryEntry put(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey, @RequestBody LibraryWriteRequest request) {
		return service.put(AuthTokens.bearer(authorization), bookKey, request);
	}

	@PatchMapping("/{bookKey}")
	public LibraryEntry patch(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey, @RequestBody JsonNode body) {
		return service.patch(AuthTokens.bearer(authorization), bookKey, body);
	}

	@DeleteMapping("/{bookKey}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey) {
		service.delete(AuthTokens.bearer(authorization), bookKey);
	}

	@PostMapping("/{bookKey}/finish")
	public LibraryEntry finish(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey, @RequestBody(required = false) FinishRequest request) {
		return service.finish(AuthTokens.bearer(authorization), bookKey, request);
	}

	@PostMapping("/{bookKey}/abandon")
	public LibraryEntry abandon(@RequestHeader(value = "Authorization", required = false) String authorization,
			@PathVariable String bookKey, @RequestBody(required = false) AbandonRequest request) {
		return service.abandon(AuthTokens.bearer(authorization), bookKey, request);
	}

	/** 409 leva a entrada atual, para o cliente fazer o merge sem outra ida ao servidor. */
	@ExceptionHandler(LibraryConflictException.class)
	ResponseEntity<Map<String, Object>> conflict(LibraryConflictException exception) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", exception.getMessage());
		body.put("code", "CONFLICT");
		body.put("current", exception.current());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadable() {
		return ResponseEntity.badRequest().body(new ApiError("Corpo inválido."));
	}

	private static Integer integer(String raw, String message) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.valueOf(raw.trim());
		} catch (NumberFormatException exception) {
			throw new AuthException(400, message);
		}
	}

	private static BigDecimal decimal(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(raw.trim());
		} catch (NumberFormatException exception) {
			throw new AuthException(400, "Nota inválida.");
		}
	}
}
