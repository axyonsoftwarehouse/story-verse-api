package com.storyverse.api.catalog;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthTokens;

/** Catálogo público. Metadados são abertos; o texto exige sessão. */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

	private final CatalogService service;
	private final CatalogCollectionService collections;
	private final CatalogReadersService readers;

	public CatalogController(CatalogService service, CatalogCollectionService collections,
			CatalogReadersService readers) {
		this.service = service;
		this.collections = collections;
		this.readers = readers;
	}

	@GetMapping("/books")
	public Map<String, Object> list(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String genre,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) String author,
			@RequestParam(required = false) String collection,
			@RequestParam(required = false) Boolean featured,
			@RequestParam(required = false) String sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int limit) {
		return service.list(q, genre, language, author, collection, featured, sort, page, limit);
	}

	@GetMapping("/books/{bookKey}")
	public Map<String, Object> get(@PathVariable String bookKey) {
		Map<String, Object> book = service.get(bookKey);
		book.put("collections", collections.collectionsForBookKey(bookKey));
		return book;
	}

	/** Quantas pessoas leram cada livro: {@code ?keys=gb:1342,cat:<uuid>} → {"gb:1342": 12, …}. */
	@GetMapping("/readers")
	public Map<String, Long> readers(@RequestParam(required = false) List<String> keys) {
		return readers.counts(keys);
	}

	@GetMapping("/collections")
	public List<Map<String, Object>> collections() {
		return collections.publicList();
	}

	@GetMapping("/collections/{slug}")
	public Map<String, Object> collection(@PathVariable String slug) {
		return collections.publicGet(slug);
	}

	@GetMapping(value = "/books/{bookKey}/text", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> text(
			@PathVariable String bookKey,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_PLAIN)
				.body(service.text(bookKey, AuthTokens.bearer(authorization)));
	}
}
