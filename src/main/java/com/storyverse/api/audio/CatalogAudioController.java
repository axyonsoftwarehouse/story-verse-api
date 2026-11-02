package com.storyverse.api.audio;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Curadoria do áudio no catálogo. Moderador cadastra; apagar exige superadmin. */
@RestController
@RequestMapping("/api/v1/admin/catalog")
public class CatalogAudioController {

	private final CatalogAudioService service;

	public CatalogAudioController(CatalogAudioService service) {
		this.service = service;
	}

	@GetMapping("/books/{bookId}/audio")
	public Map<String, Object> list(
			@PathVariable UUID bookId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.list(AuthTokens.bearer(authorization), bookId);
	}

	@PostMapping("/books/{bookId}/audio")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(
			@PathVariable UUID bookId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.create(AuthTokens.bearer(authorization), bookId, body);
	}

	@GetMapping("/audio/{audioId}")
	public Map<String, Object> get(
			@PathVariable UUID audioId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.get(AuthTokens.bearer(authorization), audioId);
	}

	@PatchMapping("/audio/{audioId}")
	public Map<String, Object> update(
			@PathVariable UUID audioId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.update(AuthTokens.bearer(authorization), audioId, body);
	}

	@PostMapping("/audio/{audioId}/upload-url")
	public Map<String, Object> uploadUrl(
			@PathVariable UUID audioId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.uploadUrl(AuthTokens.bearer(authorization), audioId, body);
	}

	@PostMapping("/audio/{audioId}/chapters")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> createChapter(
			@PathVariable UUID audioId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.createChapter(AuthTokens.bearer(authorization), audioId, body);
	}

	@PatchMapping("/audio/{audioId}/chapters/{chapterIndex}")
	public Map<String, Object> updateChapter(
			@PathVariable UUID audioId,
			@PathVariable int chapterIndex,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.updateChapter(AuthTokens.bearer(authorization), audioId, chapterIndex, body);
	}

	@PostMapping("/audio/{audioId}/publish")
	public Map<String, Object> publish(
			@PathVariable UUID audioId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.publish(AuthTokens.bearer(authorization), audioId);
	}

	@PostMapping("/audio/{audioId}/hide")
	public Map<String, Object> hide(
			@PathVariable UUID audioId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.hide(AuthTokens.bearer(authorization), audioId);
	}

	@DeleteMapping("/audio/{audioId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@PathVariable UUID audioId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), audioId);
	}
}
