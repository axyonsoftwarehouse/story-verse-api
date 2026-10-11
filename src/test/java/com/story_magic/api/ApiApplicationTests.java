package com.story_magic.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.story_magic.api.integrations.ExternalServiceException;
import com.story_magic.api.integrations.gutenberg.BookContentProvider;
import com.story_magic.api.integrations.openlibrary.OpenLibraryBookDocument;
import com.story_magic.api.integrations.openlibrary.OpenLibrarySearchProvider;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BookContentProvider contentProvider;

	@MockitoBean
	private OpenLibrarySearchProvider openLibrary;

	@Test
	void healthEndpointReturnsStatusAndTimestamp() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"))
				.andExpect(jsonPath("$.timestamp").isNotEmpty());
	}

	@Test
	void booksEndpointListsBooksAndSupportsSearch() throws Exception {
		mockMvc.perform(get("/api/v1/books"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(5))
				.andExpect(jsonPath("$[0].id").value("gb-74475"))
				.andExpect(jsonPath("$[0].gutenbergId").value(74475))
				.andExpect(jsonPath("$[0].title").value("A Escrava Isaura"))
				.andExpect(jsonPath("$[0].author").value("Bernardo Guimarães"))
				.andExpect(jsonPath("$[0].genre").value("Romance"))
				.andExpect(jsonPath("$[0].blurb").isNotEmpty())
				.andExpect(jsonPath("$[0].coverUrl").isNotEmpty())
				.andExpect(jsonPath("$[0].textLanguage").value("pt"))
				.andExpect(jsonPath("$[0].shelf").value("pt"));

		mockMvc.perform(get("/api/v1/books").param("q", "iracema"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].author").value("José de Alencar"));
	}

	@Test
	void booksEndpointReturnsNotFoundForUnknownBook() throws Exception {
		mockMvc.perform(get("/api/v1/books/{id}", "gb-unknown"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Livro não encontrado"));
	}

	@Test
	void booksEndpointAllowsLocalFrontendOrigin() throws Exception {
		mockMvc.perform(get("/api/v1/books").header("Origin", "http://localhost:5173"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Test
	void booksEndpointDoesNotAllowUnconfiguredOrigins() throws Exception {
		mockMvc.perform(get("/api/v1/books").header("Origin", "https://untrusted.example"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
				.andExpect(content().string("Invalid CORS request"));
	}

	@Test
	void bookByIdReturnsBookWithTheExpectedContract() throws Exception {
		mockMvc.perform(get("/api/v1/books/{id}", "gb-67740"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("gb-67740"))
				.andExpect(jsonPath("$.gutenbergId").value(67740))
				.andExpect(jsonPath("$.title").value("Iracema"))
				.andExpect(jsonPath("$.author").value("José de Alencar"));
	}

	@Test
	void bookContentEndpointDelegatesToGutenbergProvider() throws Exception {
		when(contentProvider.fetchPlainText(67740)).thenReturn("Texto do livro");

		mockMvc.perform(get("/api/v1/books/{id}/content", "gb-67740"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/plain"))
				.andExpect(content().string("Texto do livro"));
	}

	@Test
	void contentEndpointReturnsNotFoundWithoutCallingProviderForUnknownBook() throws Exception {
		mockMvc.perform(get("/api/v1/books/{id}/content", "gb-unknown"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.title").value("Livro não encontrado"));
		org.mockito.Mockito.verifyNoInteractions(contentProvider);
	}

	@Test
	void externalProviderFailuresReturnGatewayErrorDetails() throws Exception {
		when(contentProvider.fetchPlainText(67740))
				.thenThrow(new ExternalServiceException(
						HttpStatus.BAD_GATEWAY,
						"Não foi possível obter o texto.",
						new IllegalStateException("provider unavailable")));

		mockMvc.perform(get("/api/v1/books/{id}/content", "gb-67740"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.title").value("Falha em serviço externo"))
				.andExpect(jsonPath("$.status").value(502));
	}

	@Test
	void externalSearchProviderFailuresReturnGatewayErrorDetails() throws Exception {
		when(openLibrary.search("failing query"))
				.thenThrow(new ExternalServiceException(
						HttpStatus.BAD_GATEWAY,
						"Não foi possível consultar o Open Library.",
						new IllegalStateException("private upstream response")));

		mockMvc.perform(get("/api/v1/external-books").param("q", "failing query"))
				.andExpect(status().isBadGateway())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.title").value("Falha em serviço externo"))
				.andExpect(jsonPath("$.detail").value("Não foi possível consultar o Open Library."))
				.andExpect(jsonPath("$.status").value(502));
	}

	@Test
	void externalBookSearchReturnsFilteredPortugueseEditionSuggestions() throws Exception {
		var portugueseEdition = new OpenLibraryBookDocument.Edition("Crepúsculo", List.of("por"), 12345);
		var document = new OpenLibraryBookDocument(
				"Twilight",
				List.of("Stephenie Meyer"),
				2005,
				54321,
				20,
				new OpenLibraryBookDocument.Editions(List.of(portugueseEdition)));
		var englishOnlyDocument = new OpenLibraryBookDocument(
				"Twilight: Another story",
				List.of("Stephenie Meyer"),
				2006,
				54322,
				5,
				new OpenLibraryBookDocument.Editions(List.of(
						new OpenLibraryBookDocument.Edition("Twilight: Another story", List.of("eng"), 54322))));
		when(openLibrary.search("crepusculo")).thenReturn(List.of(document, englishOnlyDocument));

		mockMvc.perform(get("/api/v1/external-books").param("q", "crepusculo"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Crepúsculo"))
				.andExpect(jsonPath("$[0].coverUrl").value("https://covers.openlibrary.org/b/id/12345-M.jpg"));
	}

	@Test
	void externalBookSearchRejectsBlankQueries() throws Exception {
		mockMvc.perform(get("/api/v1/external-books").param("q", " "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Busca inválida"));
	}

	@Test
	void externalBookSearchRejectsSymbolOnlyQueriesWithoutCallingProvider() throws Exception {
		mockMvc.perform(get("/api/v1/external-books").param("q", "!?---"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.title").value("Busca inválida"));

		org.mockito.Mockito.verifyNoInteractions(openLibrary);
	}

	@Test
	void externalBookSearchRejectsQueriesLongerThanTheDocumentedLimit() throws Exception {
		mockMvc.perform(get("/api/v1/external-books").param("q", "x".repeat(201)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Busca inválida"));
		org.mockito.Mockito.verifyNoInteractions(openLibrary);
	}

	@Test
	void externalProviderTimeoutIsReturnedAsGatewayTimeout() throws Exception {
		when(openLibrary.search("slow query"))
				.thenThrow(new ExternalServiceException(
						HttpStatus.GATEWAY_TIMEOUT,
						"A busca no Open Library demorou demais para responder.",
						new java.net.http.HttpTimeoutException("timeout")));

		mockMvc.perform(get("/api/v1/external-books").param("q", "slow query"))
				.andExpect(status().isGatewayTimeout())
				.andExpect(jsonPath("$.status").value(504))
				.andExpect(jsonPath("$.title").value("Falha em serviço externo"));
	}

	@Test
	void unexpectedErrorsAreLoggedButNotExposedToClients() throws Exception {
		when(openLibrary.search("failing query"))
				.thenThrow(new IllegalStateException("private internal detail"));

		mockMvc.perform(get("/api/v1/external-books").param("q", "failing query"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.title").value("Erro interno"))
				.andExpect(jsonPath("$.detail").value("Não foi possível concluir a solicitação."));
	}

}
