package com.storyverse.api.reading;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

@RestController
@RequestMapping("/api/v1/me/reading-state")
public class ReadingStateController {

	private final ReadingStateService service;

	public ReadingStateController(ReadingStateService service) {
		this.service = service;
	}

	@GetMapping
	public ReadingStateResponse get(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.get(AuthTokens.bearer(authorization));
	}

	@PutMapping
	public ReadingStateWriteResponse put(@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody ReadingStateWriteRequest request) {
		return service.put(AuthTokens.bearer(authorization), request);
	}
}
