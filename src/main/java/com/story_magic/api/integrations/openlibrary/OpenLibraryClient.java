package com.story_magic.api.integrations.openlibrary;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.story_magic.api.integrations.ExternalServiceException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OpenLibraryClient implements OpenLibrarySearchProvider {

	private static final String SEARCH_URL = "https://openlibrary.org/search.json";
	private static final String USER_AGENT = "StoryMagic/1.0 (open book catalog)";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final String FIELDS = "title,author_name,first_publish_year,cover_i,edition_count,"
			+ "editions,editions.title,editions.language,editions.cover_i";

	private final HttpClient httpClient;
	private final URI searchEndpoint;
	private final Duration requestTimeout;
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	public OpenLibraryClient() {
		this(HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build(), URI.create(SEARCH_URL), REQUEST_TIMEOUT);
	}

	OpenLibraryClient(HttpClient httpClient, URI searchEndpoint, Duration requestTimeout) {
		this.httpClient = httpClient;
		this.searchEndpoint = searchEndpoint;
		this.requestTimeout = requestTimeout;
	}

	@Override
	public List<OpenLibraryBookDocument> search(String query) {
		String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		URI uri = URI.create(searchEndpoint
				+ "?q=" + encodedQuery
				+ "&fields=" + URLEncoder.encode(FIELDS, StandardCharsets.UTF_8)
				+ "&limit=20&lang=pt");
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(requestTimeout)
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/json")
				.GET()
				.build();

		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new ExternalServiceException(
						HttpStatus.BAD_GATEWAY,
						"Open Library respondeu com HTTP " + response.statusCode() + ".",
						null);
			}
			return parseResponse(response.body());
		} catch (HttpTimeoutException exception) {
			throw new ExternalServiceException(
					HttpStatus.GATEWAY_TIMEOUT,
					"A busca no Open Library demorou demais para responder.",
					exception);
		} catch (IOException exception) {
			throw new ExternalServiceException(
					HttpStatus.BAD_GATEWAY,
					"Não foi possível consultar o Open Library.",
					exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ExternalServiceException(
					HttpStatus.BAD_GATEWAY,
					"A consulta ao Open Library foi interrompida.",
					exception);
		} catch (JacksonException exception) {
			throw new ExternalServiceException(
					HttpStatus.BAD_GATEWAY,
					"O Open Library retornou uma resposta inválida.",
					exception);
		}
	}

	private record SearchResponse(List<OpenLibraryBookDocument> docs) {
	}

	static List<OpenLibraryBookDocument> parseResponse(String body) throws JacksonException {
		SearchResponse result = JSON_MAPPER.readValue(body, SearchResponse.class);
		return result.docs() == null ? List.of() : result.docs();
	}
}
