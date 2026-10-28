package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.storyverse.api.auth.*;
import com.stripe.model.Charge;
import com.stripe.model.checkout.Session;

/** Pix pré-pago com a V31 e a RLS de verdade. Rode só num PostgreSQL descartável. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class BillingPrepaidIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-pix-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
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
  @Autowired BillingService billing;
  @Autowired BillingWebhookService webhooks;
  @Autowired BillingRepository repository;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;

  UUID signup(String name) {
    AuthResponse r=(AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", name));
    return UUID.fromString(r.user().id().toString());
  }

  void asWebhook(Runnable call) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> { repository.beginWrite(); call.run(); });
  }

  Session paid(UUID user, String id) {
    Session s=new Session();
    s.setId(id); s.setMode("payment"); s.setPaymentStatus("paid");
    s.setClientReferenceId(user.toString()); s.setPaymentIntent("pi_"+id);
    s.setAmountTotal(990L); s.setCurrency("brl"); s.setMetadata(Map.of("kind","prepaid","days","30"));
    return s;
  }

  int rows(UUID user) {
    return jdbc.sql("select count(*) from public.billing_prepaid where user_id=:u").param("u",user).query(Integer.class).single();
  }

  @Test void pagarLiberaSomaEReembolsoTira() {
    UUID leitora=signup("Leitora Pix");
    assertFalse(billing.access(leitora).active());

    String primeiro="cs_"+UUID.randomUUID();
    asWebhook(() -> webhooks.prepaid(paid(leitora, primeiro)));
    asWebhook(() -> webhooks.prepaid(paid(leitora, primeiro)));
    assertEquals(1, rows(leitora), "a mesma sessão repetida não cria outro período");
    BillingService.Access acesso=billing.access(leitora);
    assertTrue(acesso.active());
    assertEquals("pix", acesso.reason());
    Instant fim1=Instant.parse(acesso.accessUntil());
    assertTrue(Duration.between(Instant.now(), fim1).toDays() >= 29);

    String segundo="cs_"+UUID.randomUUID();
    asWebhook(() -> webhooks.prepaid(paid(leitora, segundo)));
    Instant fim2=Instant.parse(billing.access(leitora).accessUntil());
    assertEquals(Duration.ofDays(30), Duration.between(fim1, fim2), "pagar antes do fim soma 30 dias");

    Charge c1=new Charge(); c1.setPaymentIntent("pi_"+primeiro);
    Charge c2=new Charge(); c2.setPaymentIntent("pi_"+segundo);
    asWebhook(() -> { webhooks.refund(c1); webhooks.refund(c2); });
    assertFalse(billing.access(leitora).active());
  }

  @Test void avisoPegaSoOFimDaSequenciaUmaVez() {
    UUID leitor=signup("Leitor Pix");
    asWebhook(() -> repository.insertPrepaid(new LinkedHashMap<>(Map.of(
        "user_id", leitor, "session_id", "cs_"+UUID.randomUUID(), "payment_intent", "pi_"+UUID.randomUUID(),
        "amount", 990, "currency", "brl", "days", 30,
        "starts_at", Timestamp.from(Instant.now().minus(Duration.ofDays(28))),
        "ends_at", Timestamp.from(Instant.now().plus(Duration.ofDays(2)))))));
    List<Map<String,Object>> ending=new ArrayList<>();
    asWebhook(() -> ending.addAll(repository.prepaidEndingSoon(Instant.now().plus(Duration.ofDays(3))).stream()
        .filter(r -> leitor.equals(r.get("user_id"))).toList()));
    assertEquals(1, ending.size());
    assertNotNull(ending.get(0).get("email"));

    asWebhook(() -> repository.markPrepaidReminded((UUID) ending.get(0).get("id")));
    List<Map<String,Object>> depois=new ArrayList<>();
    asWebhook(() -> depois.addAll(repository.prepaidEndingSoon(Instant.now().plus(Duration.ofDays(3))).stream()
        .filter(r -> leitor.equals(r.get("user_id"))).toList()));
    assertTrue(depois.isEmpty());
  }
}
