package com.storyverse.api.storage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StorageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void downloadWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/storage/user-books/object").param("path", "x/y.gz"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Não autenticado."));
	}

	@Test
	void publicDownloadOfPrivateBucketIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/storage/public/user-books/x/y.gz"))
				.andExpect(status().isNotFound());
	}
}
