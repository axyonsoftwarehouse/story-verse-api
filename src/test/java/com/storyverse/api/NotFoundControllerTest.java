package com.storyverse.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NotFoundControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rootAndFaviconAre404() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isNotFound());
		mockMvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
	}
}
