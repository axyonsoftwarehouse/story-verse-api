package com.storyverse.api.groups;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.storyverse.api.auth.AuthTokens;

/** Grupos com chat. Todas as rotas exigem conta e a feature {@code groups}. */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

	private final GroupService service;

	public GroupController(GroupService service) {
		this.service = service;
	}

	@GetMapping
	public List<Map<String, Object>> list(
			@RequestParam(required = false, defaultValue = "mine") String scope,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String book,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		String token = AuthTokens.bearer(authorization);
		return "public".equals(scope) ? service.publicGroups(token, q, book, limit) : service.myGroups(token);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.create(AuthTokens.bearer(authorization), body);
	}

	/** Eventos de todos os grupos da conta (mensagem, apagada, reação, membros). */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.connect(AuthTokens.bearer(authorization));
	}

	@PostMapping("/join/{code}")
	public Map<String, Object> joinByInvite(
			@PathVariable String code,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.joinByInvite(AuthTokens.bearer(authorization), code);
	}

	@GetMapping("/{id}")
	public Map<String, Object> group(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.group(AuthTokens.bearer(authorization), id);
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

	@PostMapping("/{id}/join")
	public Map<String, Object> join(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.join(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/{id}/leave")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.leave(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/{id}/invite/rotate")
	public Map<String, Object> rotateInvite(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.rotateInvite(AuthTokens.bearer(authorization), id);
	}

	@PostMapping("/{id}/mute")
	public Map<String, Object> mute(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setMuted(AuthTokens.bearer(authorization), id, body);
	}

	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.markRead(AuthTokens.bearer(authorization), id, body);
	}

	@DeleteMapping("/{id}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeMember(
			@PathVariable UUID id,
			@PathVariable UUID userId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.removeMember(AuthTokens.bearer(authorization), id, userId);
	}

	@PatchMapping("/{id}/members/{userId}")
	public Map<String, Object> setRole(
			@PathVariable UUID id,
			@PathVariable UUID userId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.setRole(AuthTokens.bearer(authorization), id, userId, body);
	}

	@GetMapping("/{id}/messages")
	public List<Map<String, Object>> messages(
			@PathVariable UUID id,
			@RequestParam(required = false) Long before,
			@RequestParam(required = false) Long after,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.messages(AuthTokens.bearer(authorization), id, before, after, limit);
	}

	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> send(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.send(AuthTokens.bearer(authorization), id, body);
	}

	@DeleteMapping("/messages/{messageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteMessage(
			@PathVariable long messageId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.deleteMessage(AuthTokens.bearer(authorization), messageId);
	}

	@PutMapping("/messages/{messageId}/reactions")
	public List<Map<String, Object>> react(
			@PathVariable long messageId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.react(AuthTokens.bearer(authorization), messageId, (String) body.get("emoji"), true);
	}

	@DeleteMapping("/messages/{messageId}/reactions")
	public List<Map<String, Object>> unreact(
			@PathVariable long messageId,
			@RequestParam String emoji,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.react(AuthTokens.bearer(authorization), messageId, emoji, false);
	}

	@PostMapping("/messages/{messageId}/report")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> report(
			@PathVariable long messageId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.report(AuthTokens.bearer(authorization), messageId, body);
	}
}
