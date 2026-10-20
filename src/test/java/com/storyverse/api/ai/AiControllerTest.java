package com.storyverse.api.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void statusIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/ai/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.live").value(false));
	}

	@Test
	void replyWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/v1/ai/reply")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void quotaWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/ai/quota"))
				.andExpect(status().isUnauthorized());
	}
}
