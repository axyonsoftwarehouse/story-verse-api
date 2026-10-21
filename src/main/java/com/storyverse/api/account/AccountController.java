package com.storyverse.api.account;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;
import com.storyverse.api.auth.DeleteAccountRequest;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

	private final AccountService service;

	public AccountController(AccountService service) {
		this.service = service;
	}

	@GetMapping("/export")
	public Map<String, Object> export(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.export(AuthTokens.bearer(authorization));
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@RequestBody(required = false) DeleteAccountRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), request == null ? null : request.password());
	}
}
