package com.story_magic.api.catalog.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.story_magic.api.catalog.application.BookSuggestionService;

@RestController
@RequestMapping("/api/v1/external-books")
public class ExternalBookSearchController {

	private final BookSuggestionService suggestionService;

	public ExternalBookSearchController(BookSuggestionService suggestionService) {
		this.suggestionService = suggestionService;
	}

	@GetMapping
	public List<BookSuggestionResponse> search(@RequestParam String q) {
		return suggestionService.search(q).stream()
				.map(BookSuggestionResponse::from)
				.toList();
	}
}
