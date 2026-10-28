package com.storyverse.api.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A contagem em si roda no Postgres (AdminIntegrationTest); aqui só a validação da rota pública. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogReadersControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void withoutKeysIsBadRequest() throws Exception {
		mockMvc.perform(get("/api/v1/catalog/readers"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Informe os livros."));
	}

	@Test
	void localOrMalformedKeysAreRejected() throws Exception {
		mockMvc.perform(get("/api/v1/catalog/readers").param("keys", "gb:1,local:-5"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Livro inválido."));
		mockMvc.perform(get("/api/v1/catalog/readers").param("keys", "gb:1'; drop table x"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void tooManyKeysAreRejected() throws Exception {
		String keys = String.join(",", java.util.stream.IntStream.range(0, 61).mapToObj(i -> "gb:" + i).toList());
		mockMvc.perform(get("/api/v1/catalog/readers").param("keys", keys))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("No máximo 60 livros por consulta."));
	}
}
