package com.storyverse.api.presence;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.storyverse.api.auth.AuthException;

/** Presença por SSE. Público (sem sessão): o EventSource não manda cabeçalho de autenticação. */
@RestController
@RequestMapping("/api/v1/presence")
public class PresenceController {

	private final PresenceService presence;

	public PresenceController(PresenceService presence) {
		this.presence = presence;
	}

	@GetMapping(value = "/{room}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter subscribe(@PathVariable String room, @RequestParam(required = false) String key) {
		String clientKey = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
		return presence.join(sanitize(room), clientKey);
	}

	private static String sanitize(String room) {
		if (room == null || room.isBlank()) {
			throw new AuthException(400, "Sala inválida.");
		}
		String cleaned = room.replaceAll("[^a-zA-Z0-9:_-]", "");
		if (cleaned.isEmpty()) {
			throw new AuthException(400, "Sala inválida.");
		}
		return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
	}
}
