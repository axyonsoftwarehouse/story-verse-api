package com.storyverse.api.forum;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Fila de moderacao do forum. As permissoes sao checadas no servico. */
@RestController
@RequestMapping("/api/v1/admin/forum")
public class ForumAdminController {

	private final ForumService service;

	public ForumAdminController(ForumService service) {
		this.service = service;
	}

	@GetMapping("/reports")
	public List<Map<String, Object>> reports(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.adminReports(AuthTokens.bearer(authorization), status, limit);
	}

	@PatchMapping("/reports/{id}")
	public Map<String, Object> resolveReport(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.resolveReport(AuthTokens.bearer(authorization), id, body);
	}

	@GetMapping("/topics")
	public List<Map<String, Object>> topics(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.adminTopics(AuthTokens.bearer(authorization), status, limit);
	}

	@PatchMapping("/topics/{id}")
	public Map<String, Object> moderateTopic(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.moderateTopic(AuthTokens.bearer(authorization), id, body);
	}

	@GetMapping("/replies")
	public List<Map<String, Object>> replies(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.adminReplies(AuthTokens.bearer(authorization), status, limit);
	}

	@PatchMapping("/replies/{id}")
	public Map<String, Object> moderateReply(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.moderateReply(AuthTokens.bearer(authorization), id, body);
	}
}
