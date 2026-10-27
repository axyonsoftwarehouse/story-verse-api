package com.storyverse.api.forum;

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

/**
 * Forum. Com a cobranca ligada, o BillingAccessFilter exige sessao e assinatura em todas as rotas;
 * sem ela, a leitura nao exige sessao. Escrever sempre exige conta.
 */
@RestController
@RequestMapping("/api/v1/forum")
public class ForumController {

	private final ForumService service;

	public ForumController(ForumService service) {
		this.service = service;
	}

	@GetMapping("/spaces")
	public List<Map<String, Object>> spaces() {
		return service.spaces();
	}

	@GetMapping("/topics")
	public ForumPage topics(
			@RequestParam(required = false) String space,
			@RequestParam(required = false) String book,
			@RequestParam(required = false, defaultValue = "recent") String filter,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.topics(AuthTokens.bearer(authorization), space, book, filter, q, cursor, limit);
	}

	@PostMapping("/topics")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> createTopic(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.createTopic(AuthTokens.bearer(authorization), body);
	}

	@GetMapping("/topics/{id}")
	public Map<String, Object> topic(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.topic(AuthTokens.bearer(authorization), id);
	}

	@PatchMapping("/topics/{id}")
	public Map<String, Object> updateTopic(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.updateTopic(AuthTokens.bearer(authorization), id, body);
	}

	@DeleteMapping("/topics/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTopic(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.deleteTopic(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/topics/{id}/replies")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> createReply(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.createReply(AuthTokens.bearer(authorization), id, body);
	}

	@GetMapping("/topics/{id}/replies")
	public ForumPage replies(
			@PathVariable UUID id,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.replies(AuthTokens.bearer(authorization), id, cursor, limit);
	}

	@PatchMapping("/replies/{id}")
	public Map<String, Object> updateReply(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.updateReply(AuthTokens.bearer(authorization), id, body);
	}

	@DeleteMapping("/replies/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteReply(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.deleteReply(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/topics/{id}/reaction")
	public Map<String, Object> reactToTopic(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.reactToTopic(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/replies/{id}/reaction")
	public Map<String, Object> reactToReply(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.reactToReply(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/topics/{id}/bookmark")
	public Map<String, Object> toggleBookmark(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.toggleBookmark(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/topics/{id}/accept-reply")
	public Map<String, Object> acceptReply(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.acceptReply(AuthTokens.bearer(authorization), id, body);
	}

	@PostMapping("/reports")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> report(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.report(AuthTokens.bearer(authorization), body);
	}

	@GetMapping("/me/bookmarks")
	public List<Map<String, Object>> bookmarks(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.bookmarks(AuthTokens.bearer(authorization));
	}
}
