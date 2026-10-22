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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService service;
	private final AuthCookieService cookies;

	public AuthController(AuthService service, AuthCookieService cookies) {
		this.service = service;
		this.cookies = cookies;
	}

	@PostMapping("/signup")
	public ResponseEntity<?> signup(@RequestBody SignupRequest request, HttpServletResponse response) {
		Object result = service.signup(request);
		if (result instanceof AuthResponse auth) {
			return ResponseEntity.status(HttpStatus.CREATED).body(respond(auth, response));
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
	}

	@PostMapping("/login")
	public AuthView login(@RequestBody LoginRequest request, HttpServletResponse response) {
		return respond(service.login(request), response);
	}

	@PostMapping("/confirm-email")
	public AuthView confirmEmail(@RequestBody TokenRequest request, HttpServletResponse response) {
		return respond(service.confirmEmail(request.token()), response);
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
	public void resetPassword(@RequestBody ResetPasswordRequest request, HttpServletResponse response) {
		service.resetPassword(request.token(), request.password());
		cookies.clear(response);
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
	public AuthView refresh(@RequestBody(required = false) RefreshRequest request,
			HttpServletRequest httpRequest, HttpServletResponse response) {
		String token = refreshToken(request, httpRequest);
		if (token == null || token.isBlank()) {
			throw new AuthException(401, "Não autenticado.");
		}
		try {
			return respond(service.refresh(token), response);
		} catch (AuthException exception) {
			// Token inválido/expirado: limpa o cookie para o navegador não repetir em loop.
			cookies.clear(response);
			throw exception;
		}
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@RequestBody(required = false) RefreshRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			HttpServletRequest httpRequest, HttpServletResponse response) {
		try {
			service.logout(refreshToken(request, httpRequest), AuthTokens.bearer(authorization));
		} finally {
			// Mesmo se a revogação falhar no banco, o navegador não pode continuar com o cookie.
			cookies.clear(response);
		}
	}

	private AuthView respond(AuthResponse auth, HttpServletResponse response) {
		cookies.set(response, auth.refreshToken());
		return AuthView.from(auth);
	}

	private String refreshToken(RefreshRequest request, HttpServletRequest httpRequest) {
		if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
			return request.refreshToken();
		}
		return cookies.read(httpRequest);
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
