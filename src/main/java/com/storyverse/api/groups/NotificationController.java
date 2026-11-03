package com.storyverse.api.groups;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Sininho do app: respostas, reações e entradas nos grupos da conta. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

	private final GroupService service;

	public NotificationController(GroupService service) {
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.notifications(AuthTokens.bearer(authorization));
	}

	@PostMapping("/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.readNotifications(AuthTokens.bearer(authorization));
	}
}
