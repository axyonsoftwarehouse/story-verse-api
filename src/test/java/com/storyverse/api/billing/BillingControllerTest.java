package com.storyverse.api.billing;

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
class BillingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void plansIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/billing/plans"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plans[0].trialDays").value(7));
	}

	@Test
	void statusWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/billing/status"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void webhookWithoutSignatureIsRejected() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.post("/api/v1/billing/webhook")
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}
}
