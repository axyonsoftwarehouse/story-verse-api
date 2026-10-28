package com.storyverse.api.feature;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.storyverse.api.auth.*;

/** Estante ligada para todas as contas pela configuração. Rode só num PostgreSQL descartável. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class FeatureEveryoneIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-feature-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.auth.require-email-confirmation", () -> "false");
    properties.add("app.public-api-url", () -> "https://api.example.test");
    properties.add("app.security.require-runtime-role", () -> "false");
    properties.add("app.features.everyone", () -> "library");
  }
  @Autowired AuthService auth;
  @Autowired FeatureAccessService features;
  @Autowired JdbcClient jdbc;

  UUID user() {
    AuthResponse session=(AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Leitor"));
    return UUID.fromString(session.user().id().toString());
  }

  @Test void everyAccountHasLibraryWithoutGrant() {
    UUID plain=user();
    assertEquals(List.of("library"),features.features(plain));
    assertTrue(features.enabled(plain,"library"));
    features.require(plain,"library");
  }

  @Test void catalogMarksFeatureAsOnForEveryone() {
    UUID admin=user();
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'superadmin')").param("id",admin).update();
    FeatureCatalogEntry library=features.catalog(admin).stream()
        .filter(entry->entry.feature().equals("library")).findFirst().orElseThrow();
    assertTrue(library.everyone());
    assertEquals(409,assertThrows(AuthException.class,()->features.grant(admin,user(),"library",true)).status());
  }
}
