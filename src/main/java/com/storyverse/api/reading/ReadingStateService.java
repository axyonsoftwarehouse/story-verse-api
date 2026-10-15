package com.storyverse.api.reading;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReadingStateService {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final ReadingStateRepository repository;
	private final AuthService auth;

	public ReadingStateService(ReadingStateRepository repository, AuthService auth) {
		this.repository = repository;
		this.auth = auth;
	}

	@Transactional(readOnly = true)
	public ReadingStateResponse get(String accessToken) {
		UUID userId = auth.authenticate(accessToken);
		return repository.find(userId)
				.map(row -> new ReadingStateResponse(readTree(row.data()), row.updatedAt()))
				.orElseGet(() -> new ReadingStateResponse(emptyObject(), null));
	}

	@Transactional
	public ReadingStateWriteResponse put(String accessToken, ReadingStateWriteRequest request) {
		UUID userId = auth.authenticate(accessToken);
		String dataJson = request.data() == null ? "{}" : request.data().toString();
		// baseUpdatedAt null => primeira gravação; senão, grava só se a nuvem não mudou (otimista).
		Optional<String> updated = request.baseUpdatedAt() == null
				? repository.insertIfAbsent(userId, dataJson)
				: repository.updateIfMatches(userId, dataJson, request.baseUpdatedAt());
		String updatedAt = updated.orElseGet(() -> repository.find(userId).map(StateRow::updatedAt).orElse(null));
		return new ReadingStateWriteResponse(updated.isPresent(), updatedAt);
	}

	private JsonNode readTree(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception exception) {
			return emptyObject();
		}
	}

	private JsonNode emptyObject() {
		try {
			return MAPPER.readTree("{}");
		} catch (Exception exception) {
			throw new IllegalStateException("Falha ao montar o objeto vazio", exception);
		}
	}
}
