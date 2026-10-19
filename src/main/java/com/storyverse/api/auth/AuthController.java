package com.storyverse.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
	public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
		Object result = service.signup(request);
		return ResponseEntity.status(result instanceof AuthResponse ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
				.body(result);
	}

	@PostMapping("/login")
	public AuthResponse login(@RequestBody LoginRequest request) {
		return service.login(request);
	}

	@PostMapping("/confirm-email")
	public AuthResponse confirmEmail(@RequestBody TokenRequest request) {
		return service.confirmEmail(request.token());
	}

	@PostMapping("/resend-confirmation")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void resendConfirmation(@RequestBody EmailRequest request) {
		service.resendConfirmation(request.email());
	}

	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void forgotPassword(@RequestBody EmailRequest request) {
		service.requestPasswordReset(request.email());
	}

	@PostMapping("/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetPassword(@RequestBody ResetPasswordRequest request) {
		service.resetPassword(request.token(), request.password());
	}

	@PostMapping("/change-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@RequestBody ChangePasswordRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.changePassword(AuthTokens.bearer(authorization), request.currentPassword(), request.newPassword());
	}

	@PatchMapping("/profile")
	public UserResponse updateProfile(@RequestBody ProfileUpdateRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.updateProfile(AuthTokens.bearer(authorization), request);
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(@RequestBody RefreshRequest request) {
		return service.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@RequestBody(required = false) RefreshRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.logout(request == null ? null : request.refreshToken(), AuthTokens.bearer(authorization));
	}

	@GetMapping("/me")
	public UserResponse me(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.me(AuthTokens.bearer(authorization));
	}

	@GetMapping("/capabilities")
	public CapabilitiesResponse capabilities(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.capabilities(AuthTokens.bearer(authorization));
	}
}
