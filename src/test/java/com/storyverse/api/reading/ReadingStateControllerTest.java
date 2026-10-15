package com.storyverse.api.reading;

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
class ReadingStateControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void getWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/me/reading-state"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Não autenticado."));
	}
}
