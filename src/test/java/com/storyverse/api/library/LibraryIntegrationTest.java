package com.storyverse.api.library;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.storyverse.api.auth.*;

/** Run only against a disposable PostgreSQL database, never production. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class LibraryIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-library-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.auth.require-email-confirmation", () -> "false");
    properties.add("app.public-api-url", () -> "https://api.example.test");
    properties.add("app.rate-limit.persistent", () -> "true");
    properties.add("app.security.require-runtime-role", () -> "false");
  }
  @Autowired AuthService auth;
  @Autowired JdbcClient jdbc;

  AuthResponse userSession() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Leitor")); }
  UUID id(AuthResponse session) { return UUID.fromString(session.user().id().toString()); }
  UUID user() { return id(userSession()); }
  void grant(UUID uid) { jdbc.sql("insert into public.feature_access(user_id,feature) values(:id,'library')").param("id",uid).update(); }
  int status(Executable call) { return assertThrows(AuthException.class,call).status(); }

  void insertRaw(UUID uid,String key,String source,String status,BigDecimal rating) {
    jdbc.sql("""
        insert into public.library_entries(user_id,book_key,source,status,rating,title,finished_on)
        values(:uid,:key,:source,:status,:rating,'T',case when :rating::numeric is null then null else current_date end)
        """).param("uid",uid).param("key",key).param("source",source).param("status",status).param("rating",rating).update();
  }
  int count(String sql,UUID uid) { return jdbc.sql(sql).param("id",uid).query(Integer.class).single(); }

  @Test void schemaRejectsInvalidRows() {
    UUID uid=user();
    insertRaw(uid,"gb:1","gutenberg","reading",null);
    assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:x","gutenberg","reading",null));
    assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:2","gutenberg","paused",null));
    assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:3","gutenberg","finished",new BigDecimal("4.3")));
  }
  @Test void deletingTheAccountRemovesTheShelf() {
    UUID uid=user(); insertRaw(uid,"gb:1","gutenberg","reading",null);
    jdbc.sql("delete from auth.users where id=:id").param("id",uid).update();
    assertEquals(0,count("select count(*) from public.library_entries where user_id=:id",uid));
  }
}
