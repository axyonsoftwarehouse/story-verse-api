package com.storyverse.api.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogAudioControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void listWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/admin/catalog/books/{id}/audio", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Não autenticado."));
	}

	@Test
	void uploadUrlWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/admin/catalog/audio/{id}/upload-url", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void publishWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/admin/catalog/audio/{id}/publish", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}
}
