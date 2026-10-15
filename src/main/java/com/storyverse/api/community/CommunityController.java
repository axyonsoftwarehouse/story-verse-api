package com.storyverse.api.community;

import java.util.List;
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

/** Acervo da comunidade (`book_submissions`). Leitura de aprovados dispensa sessão. */
@RestController
@RequestMapping("/api/v1/community/submissions")
public class CommunityController {

	private final CommunityService service;

	public CommunityController(CommunityService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list(
			@RequestParam(defaultValue = "approved") String scope,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.list(AuthTokens.bearer(authorization), scope);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.create(AuthTokens.bearer(authorization), body);
	}

	@PatchMapping("/{id}")
	public Map<String, Object> patch(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		String token = AuthTokens.bearer(authorization);
		// Com "status" é revisão (admin); sem, é troca de capa (dono ou admin).
		return body.containsKey("status") ? service.review(token, id, body) : service.updateCover(token, id, body);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), id);
	}
}
