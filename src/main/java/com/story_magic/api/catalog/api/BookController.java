package com.story_magic.api.catalog.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.story_magic.api.catalog.application.BookNotFoundException;
import com.story_magic.api.catalog.application.BookService;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {

	private final BookService bookService;

	public BookController(BookService bookService) {
		this.bookService = bookService;
	}

	@GetMapping
	public List<BookResponse> search(@RequestParam(required = false) String q) {
		return bookService.search(q).stream()
				.map(BookResponse::from)
				.toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<BookResponse> findById(@PathVariable String id) {
		return bookService.findById(id)
				.map(BookResponse::from)
				.map(ResponseEntity::ok)
				.orElseThrow(() -> new BookNotFoundException(id));
	}

	@GetMapping(value = "/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> getPlainText(@PathVariable String id) {
		return ResponseEntity.ok()
				.contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
				.body(bookService.getPlainText(id));
	}
}
