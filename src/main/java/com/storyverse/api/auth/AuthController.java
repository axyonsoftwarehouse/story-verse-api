package com.storyverse.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService service;

	public AuthController(AuthService service) {
		this.service = service;
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse signup(@RequestBody SignupRequest request) {
		return service.signup(request);
	}

	@PostMapping("/login")
	public AuthResponse login(@RequestBody LoginRequest request) {
		return service.login(request);
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(@RequestBody RefreshRequest request) {
		return service.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@RequestBody(required = false) RefreshRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.logout(request == null ? null : request.refreshToken(), bearer(authorization));
	}

	@GetMapping("/me")
	public UserResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.me(bearer(authorization));
	}

	private static String bearer(String authorization) {
		if (authorization == null) {
			return null;
		}
		String header = authorization.trim();
		if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return header.substring(7).trim();
		}
		return null;
	}
}
