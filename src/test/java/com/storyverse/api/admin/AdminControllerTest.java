package com.storyverse.api.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Toda rota administrativa exige sessão. Sem Authorization, a resposta é 401 — nunca 200 vazio nem
 * 500. Os papéis são testados em {@link AdminIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void accountEndpointsRequireAuthentication() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/admin/users?q=" + id))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/users/" + id))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/admin/users/" + id + "/sessions/revoke"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/admin/users/" + id + "/roles/moderator"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/v1/admin/users/" + id + "/roles/moderator"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void overviewAndAuditRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/admin/overview"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/audit"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void dashboardEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/admin/health"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/billing/series?group=week"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/traffic"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/vitals"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void featureEndpointsRequireAuthentication() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/admin/features?userId=" + id))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/features/catalog"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/features/library/users"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/v1/admin/features")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + id + "\",\"feature\":\"library\",\"enabled\":true}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void forumModerationEndpointsRequireAuthentication() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/admin/forum/reports"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/v1/admin/forum/reports/" + id)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/forum/topics"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/v1/admin/forum/topics/" + id)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/forum/replies"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/api/v1/admin/forum/replies/" + id)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}
}
