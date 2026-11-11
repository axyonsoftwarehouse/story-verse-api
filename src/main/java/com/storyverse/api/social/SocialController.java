package com.storyverse.api.social;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Rede social (seguir perfis). Todas as rotas exigem conta e a feature {@code groups}. */
@RestController
@RequestMapping("/api/v1/social")
public class SocialController {

	private final SocialService service;

	public SocialController(SocialService service) {
		this.service = service;
	}

	/** Busca por nome ({@code q} com 2+ letras). */
	@GetMapping("/people")
	public List<Map<String, Object>> search(
			@RequestParam(required = false) String q,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.search(AuthTokens.bearer(authorization), q);
	}

	/** Quem divide grupo com a conta (ou já a segue) e ela ainda não segue. */
	@GetMapping("/people/suggestions")
	public List<Map<String, Object>> suggestions(@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.suggestions(AuthTokens.bearer(authorization));
	}

	@GetMapping("/people/{id}")
	public Map<String, Object> profile(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.profile(AuthTokens.bearer(authorization), id);
	}

	@GetMapping("/people/{id}/followers")
	public List<Map<String, Object>> followers(
			@PathVariable UUID id,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.followers(AuthTokens.bearer(authorization), id, limit, offset);
	}

	@GetMapping("/people/{id}/following")
	public List<Map<String, Object>> following(
			@PathVariable UUID id,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.following(AuthTokens.bearer(authorization), id, limit, offset);
	}

	@PutMapping("/people/{id}/follow")
	public Map<String, Object> follow(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.follow(AuthTokens.bearer(authorization), id);
	}

	@DeleteMapping("/people/{id}/follow")
	public Map<String, Object> unfollow(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.unfollow(AuthTokens.bearer(authorization), id);
	}

	/** Tira a pessoa dos seguidores da conta. */
	@DeleteMapping("/me/followers/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeFollower(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.removeFollower(AuthTokens.bearer(authorization), id);
	}

	/** Pedidos para seguir que a conta recebeu (perfil privado). */
	@GetMapping("/me/follow-requests")
	public List<Map<String, Object>> requests(
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.requests(AuthTokens.bearer(authorization), limit, offset);
	}

	/** Aceitar o pedido de {@code id}. */
	@PutMapping("/me/follow-requests/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void acceptRequest(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.acceptRequest(AuthTokens.bearer(authorization), id);
	}

	/** Recusar o pedido de {@code id}. */
	@DeleteMapping("/me/follow-requests/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void declineRequest(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.declineRequest(AuthTokens.bearer(authorization), id);
	}

	/** Livros do perfil (lendo e terminados), para o "Ver todos". */
	@GetMapping("/people/{id}/books")
	public List<Map<String, Object>> books(
			@PathVariable UUID id,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) Integer offset,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.books(AuthTokens.bearer(authorization), id, limit, offset);
	}

	/** O app publica as conquistas desbloqueadas da conta. */
	@PutMapping("/me/showcase")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void showcase(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.saveShowcase(AuthTokens.bearer(authorization), body);
	}

	/**
	 * {@code { "showReading"?: bool, "private"?: bool }}: mostrar ou esconder leituras, conquistas e
	 * presentes; perfil privado (seguir vira pedido).
	 */
	@PutMapping("/me/privacy")
	public Map<String, Object> privacy(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.savePrivacy(AuthTokens.bearer(authorization), body);
	}
}
