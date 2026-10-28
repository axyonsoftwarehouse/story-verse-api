package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.storyverse.api.auth.*;
import com.storyverse.api.common.DbSession;

/** Usuários ativos (DAU/WAU/MAU, 2.3). Rode só contra um PostgreSQL descartável, nunca produção. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class AdminActiveIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-active-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.auth.require-email-confirmation", () -> "false");
    properties.add("app.public-api-url", () -> "https://api.example.test");
    properties.add("app.security.require-runtime-role", () -> "false");
  }
  @Autowired MockMvc mvc;
  @Autowired AuthService auth;
  @Autowired AdminActiveService active;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;

  AuthResponse user() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Ativo")); }
  UUID id(AuthResponse session) { return UUID.fromString(session.user().id().toString()); }
  AuthResponse userWithRole(String role) {
    AuthResponse session=user();
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,:role)").param("id",id(session)).param("role",role).update();
    return session;
  }
  int status(Executable action) { return assertThrows(AuthException.class, action).status(); }
  void activeOn(UUID uid, String... days) {
    for (String day : days) {
      jdbc.sql("insert into public.activity_days(user_id,day) values(:id,cast(:day as date)) on conflict do nothing")
          .param("id",uid).param("day",day).update();
    }
  }
  <T> T asRuntime(UUID uid, Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(status -> {
      jdbc.sql("set local role storyverse_api").update();
      DbSession.setUser(jdbc, uid);
      return work.get();
    });
  }

  @Test void activeRequiresSuperadmin() {
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator"), plain=user();
    assertEquals(403,status(()->active.active(support.accessToken(),null,null)));
    assertEquals(403,status(()->active.active(moderator.accessToken(),null,null)));
    assertEquals(403,status(()->active.active(plain.accessToken(),null,null)));
    assertEquals(401,status(()->active.active(null,null,null)));
  }

  @Test void activeRejectsInvalidRange() {
    AuthResponse admin=userWithRole("superadmin");
    assertEquals(400,status(()->active.active(admin.accessToken(),"2026-01-01","2026-05-01")));
    assertEquals(400,status(()->active.active(admin.accessToken(),"2026-01-02","2026-01-01")));
    assertEquals(400,status(()->active.active(admin.accessToken(),"lixo",null)));
  }

  /** Março de 2001: nenhum outro teste grava atividade tão antiga. */
  @Test void activeCountsDauWauMauAndDailySeries() throws Exception {
    AuthResponse admin=userWithRole("superadmin");
    UUID a=id(user()), b=id(user()), c=id(user()), d=id(user());
    activeOn(a,"2001-03-31","2001-03-30");   // DAU, WAU e MAU (duas vezes, conta uma)
    activeOn(b,"2001-03-25");                // primeiro dia da janela de 7
    activeOn(d,"2001-03-24");                // fora da janela de 7, dentro da de 30
    activeOn(c,"2001-03-02","2001-03-01");   // 03-02 é o primeiro dia da janela de 30; 03-01 fica de fora
    AdminActive result=active.active(admin.accessToken(),"2001-03-01","2001-03-31");
    assertEquals(1,result.dau());
    assertEquals(2,result.wau());
    assertEquals(4,result.mau());
    assertEquals(31,result.series().size());
    assertEquals(LocalDate.parse("2001-03-01"),result.series().get(0).day());
    assertEquals(LocalDate.parse("2001-03-31"),result.series().get(30).day());
    Map<String,Long> byDay=new HashMap<>();
    result.series().forEach(p->byDay.put(p.day().toString(),p.active()));
    assertEquals(1,byDay.get("2001-03-01"));
    assertEquals(1,byDay.get("2001-03-30"));
    assertEquals(0,byDay.get("2001-03-15"));
    assertEquals(6,result.series().stream().mapToLong(AdminActive.Point::active).sum());

    // Janela que termina em 2001-03-25: a fica de fora; b é o DAU, d entra no WAU e c no MAU.
    AdminActive earlier=active.active(admin.accessToken(),"2001-03-20","2001-03-25");
    assertEquals(1,earlier.dau());
    assertEquals(2,earlier.wau());
    assertEquals(3,earlier.mau());

    // O papel de runtime (sem bypass de RLS) consegue ler pelas funções.
    AdminActive runtime=asRuntime(id(admin),()->active.active(admin.accessToken(),"2001-02-01","2001-03-31"));
    assertEquals(4,runtime.mau());

    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/metrics/active?from=2001-03-01&to=2001-03-31")
            .header("Authorization","Bearer "+admin.accessToken()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.dau").value(1))
        .andExpect(MockMvcResultMatchers.jsonPath("$.wau").value(2))
        .andExpect(MockMvcResultMatchers.jsonPath("$.mau").value(4))
        .andExpect(MockMvcResultMatchers.jsonPath("$.series[0].day").value("2001-03-01"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.series[0].active").value(1));
  }

  @Test void authenticatedRequestMarksTodayOnce() throws Exception {
    AuthResponse session=user();
    UUID uid=id(session);
    LocalDate today=LocalDate.now(AdminRange.SAO_PAULO);
    Supplier<Integer> rows=()->jdbc.sql("select count(*) from public.activity_days where user_id=:id")
        .param("id",uid).query(Integer.class).single();
    assertEquals(0,rows.get(), "o cadastro sozinho não conta como request autenticado");
    auth.me(session.accessToken());
    auth.authenticate(session.accessToken());
    long deadline=System.currentTimeMillis()+10_000;
    while (rows.get()==0 && System.currentTimeMillis()<deadline) Thread.sleep(50);
    assertEquals(1,rows.get());
    assertEquals(today,jdbc.sql("select day from public.activity_days where user_id=:id")
        .param("id",uid).query(LocalDate.class).single());
  }
}
