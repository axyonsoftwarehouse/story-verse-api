package com.storyverse.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.storyverse.api.common.RateLimits;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.ai.AiService;

@SpringBootTest @AutoConfigureMockMvc
class SecurityBoundaryTest {
  @Autowired MockMvc mvc;
  @Test void missingAuthenticationIsRejectedBeforeStorageBodyValidation() throws Exception {
    mvc.perform(post("/api/v1/storage/user-books/object").param("path","other/book.gz")
        .header("Content-Length","999999999")).andExpect(status().isUnauthorized());
  }
  @Test void jsonBodySizeIsLimitedBeforeBinding() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(new byte[1_048_577]))
        .andExpect(status().isPayloadTooLarge());
  }
  @Test void rateBudgetRejectsAttemptsAfterTheLimit() {
    RateLimits limits=new RateLimits();
    limits.check("test",1,Duration.ofMinutes(1));
    assertEquals(429,assertThrows(AuthException.class,()->limits.check("test",1,Duration.ofMinutes(1))).status());
  }
  @Test void blankModelConfigurationKeepsProviderDefaults() {
    assertTrue(new AiService("test-key","","","","","").live());
  }
}
