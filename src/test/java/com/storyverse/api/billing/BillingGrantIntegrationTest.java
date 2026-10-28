package com.storyverse.api.billing;

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

/** Acesso grátis manual pelo painel, com a RLS de verdade. Rode só num PostgreSQL descartável. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class BillingGrantIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-grant-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.auth.require-email-confirmation", () -> "false");
    properties.add("app.public-api-url", () -> "https://api.example.test");
    properties.add("app.security.require-runtime-role", () -> "false");
    properties.add("app.billing.enabled", () -> "true");
  }
  @Autowired AuthService auth;
  @Autowired BillingAdminService admin;
  @Autowired BillingService billing;
  @Autowired JdbcClient jdbc;

  AuthResponse signup(String name) {
    return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", name));
  }

  @Test void superadminConcedeListaERetira() {
    AuthResponse superadmin=signup("Admin Grant");
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'superadmin')")
        .param("id",UUID.fromString(superadmin.user().id().toString())).update();
    String token=superadmin.accessToken();
    UUID familia=UUID.fromString(signup("Familiar").user().id().toString());

    assertFalse(billing.access(familia).active());

    Map<String,Object> grant=admin.grant(token,familia,"Família",null);
    UUID grantId=UUID.fromString(String.valueOf(grant.get("id")));
    BillingService.Access acesso=billing.access(familia);
    assertTrue(acesso.active());
    assertEquals("grant",acesso.reason());

    List<Map<String,Object>> lista=admin.grants(token,familia);
    assertEquals(1,lista.size());
    assertEquals(Boolean.TRUE,lista.get(0).get("active"));
    assertEquals("Admin Grant",lista.get(0).get("created_by_name"));
    assertEquals(409,assertThrows(AuthException.class,()->admin.grant(token,familia,"De novo",null)).status());

    admin.revokeGrant(token,grantId);
    assertFalse(billing.access(familia).active());
    Map<String,Object> retirado=admin.grants(token,familia).get(0);
    assertEquals(Boolean.FALSE,retirado.get("active"));
    assertEquals("Admin Grant",retirado.get("revoked_by_name"));
    assertEquals(404,assertThrows(AuthException.class,()->admin.revokeGrant(token,grantId)).status());
  }

  @Test void leitorNaoConcede() {
    AuthResponse leitor=signup("Leitor");
    UUID outro=UUID.fromString(signup("Outro").user().id().toString());
    assertEquals(403,assertThrows(AuthException.class,()->admin.grant(leitor.accessToken(),outro,"Amigo",null)).status());
  }
}
