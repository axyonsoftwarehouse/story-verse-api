package com.storyverse.api.audio;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Retomada de escuta por conta. O progresso local continua sendo a primeira fonte. */
@RestController
@RequestMapping("/api/v1/me/audio")
public class AudioProgressController {

	private final CatalogAudioService service;

	public AudioProgressController(CatalogAudioService service) {
		this.service = service;
	}

	@GetMapping("/{audioId}")
	public Map<String, Object> get(
			@PathVariable UUID audioId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.progress(AuthTokens.bearer(authorization), audioId);
	}

	@PutMapping("/{audioId}")
	public Map<String, Object> put(
			@PathVariable UUID audioId,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.saveProgress(AuthTokens.bearer(authorization), audioId, body);
	}
}
