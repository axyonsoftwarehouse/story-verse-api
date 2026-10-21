package com.storyverse.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void meWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Não autenticado."));
	}

	@Test
	void capabilitiesWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/auth/capabilities"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void signupWithInvalidEmailIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"invalido\",\"password\":\"segredo1\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("E-mail inválido."));
	}

	@Test
	void signupRejectsPasswordShorterThanEight() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"a@example.test\",\"password\":\"1234567\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("A senha precisa de pelo menos 8 caracteres."));
	}

	@Test
	void accountEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(patch("/api/v1/auth/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Novo\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/auth/change-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currentPassword\":\"segredo1\",\"newPassword\":\"segredo2\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void passwordRecoveryRejectsInvalidInputs() throws Exception {
		mockMvc.perform(post("/api/v1/auth/forgot-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"invalido\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/auth/reset-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"abc\",\"password\":\"123\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/auth/confirm-email")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"\"}"))
				.andExpect(status().isBadRequest());
	}
}
