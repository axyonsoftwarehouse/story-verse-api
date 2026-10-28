package com.storyverse.api.catalog;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Cadastro e curadoria do catálogo. Moderador e superadmin; apagar exige superadmin. */
@RestController
@RequestMapping("/api/v1/admin/catalog/books")
public class CatalogAdminController {

	private final CatalogService service;

	public CatalogAdminController(CatalogService service) {
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> list(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String genre,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) String source,
			@RequestParam(required = false) Boolean featured,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.listAdmin(AuthTokens.bearer(authorization), status, q, genre, language, source,
				featured, page, limit);
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.getAdmin(AuthTokens.bearer(authorization), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.create(AuthTokens.bearer(authorization), body);
	}

	@PatchMapping("/{id}")
	public Map<String, Object> update(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.update(AuthTokens.bearer(authorization), id, body);
	}

	@PostMapping("/{id}/text")
	public Map<String, Object> replaceText(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.replaceText(AuthTokens.bearer(authorization), id, body);
	}

	@PostMapping("/{id}/publish")
	public Map<String, Object> publish(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setStatus(AuthTokens.bearer(authorization), id, "published");
	}

	@PostMapping("/{id}/hide")
	public Map<String, Object> hide(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setStatus(AuthTokens.bearer(authorization), id, "hidden");
	}

	@PostMapping("/{id}/archive")
	public Map<String, Object> archive(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setStatus(AuthTokens.bearer(authorization), id, "archived");
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), id);
	}
}
