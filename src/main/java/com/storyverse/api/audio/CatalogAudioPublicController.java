package com.storyverse.api.audio;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Manifesto e reprodução pública dos audiolivros publicados. */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogAudioPublicController {

	private final CatalogAudioService service;

	public CatalogAudioPublicController(CatalogAudioService service) {
		this.service = service;
	}

	@GetMapping("/audio")
	public Map<String, Object> list() {
		return service.publicList();
	}

	@GetMapping("/books/{bookKey}/audio")
	public Map<String, Object> manifest(@PathVariable String bookKey) {
		return service.publicManifest(bookKey);
	}

	/**
	 * Devolve uma URL assinada do R2 para o capítulo. O capítulo 1 é público; os demais
	 * exigem sessão e, quando a cobrança está ligada, assinatura ativa.
	 */
	@GetMapping("/books/{bookKey}/audio/{audioId}/chapters/{index}/stream")
	public Map<String, Object> stream(
			@PathVariable String bookKey,
			@PathVariable UUID audioId,
			@PathVariable int index,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.publicStream(AuthTokens.bearer(authorization), bookKey, audioId, index);
	}
}
