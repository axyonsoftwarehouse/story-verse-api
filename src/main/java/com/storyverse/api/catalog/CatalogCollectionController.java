package com.storyverse.api.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Coleções curadas: CRUD e ordem dos livros. Moderador e superadmin. */
@RestController
@RequestMapping("/api/v1/admin/catalog/collections")
public class CatalogCollectionController {

	private final CatalogCollectionService service;

	public CatalogCollectionController(CatalogCollectionService service) {
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

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), id);
	}

	/** Substitui os livros da coleção; a ordem do array é a ordem exibida. */
	@PutMapping("/{id}/books")
	public Map<String, Object> setBooks(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setBooks(AuthTokens.bearer(authorization), id, body);
	}
}
