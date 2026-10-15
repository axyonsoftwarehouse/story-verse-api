package com.storyverse.api.rpc;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

/** Chamada de função do banco em nome de quem está logado (lista branca no serviço). */
@RestController
@RequestMapping("/api/v1/rpc")
public class RpcController {

	private final RpcService service;
	private final AuthService auth;

	public RpcController(RpcService service, AuthService auth) {
		this.service = service;
		this.auth = auth;
	}

	@PostMapping("/{name}")
	public Object call(
			@PathVariable String name,
			@RequestBody(required = false) Map<String, Object> args,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		return service.call(userId, name, args == null ? Map.of() : args);
	}
}
