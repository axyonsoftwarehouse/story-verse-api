package com.storyverse.api.catalog;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Importação em lote do catálogo: prévia, execução, histórico e CSV de erros. */
@RestController
@RequestMapping("/api/v1/admin/catalog/imports")
public class CatalogImportController {

	private final CatalogImportService service;

	public CatalogImportController(CatalogImportService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.list(AuthTokens.bearer(authorization));
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.get(AuthTokens.bearer(authorization), id);
	}

	@PostMapping
	public Map<String, Object> run(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.run(AuthTokens.bearer(authorization), body);
	}

	@GetMapping(value = "/{id}/errors.csv", produces = "text/csv")
	public ResponseEntity<byte[]> errors(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		String csv = service.errorsCsv(AuthTokens.bearer(authorization), id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"importacao-" + id + "-erros.csv\"")
				.body(csv.getBytes(StandardCharsets.UTF_8));
	}
}
