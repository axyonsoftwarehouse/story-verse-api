package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.storyverse.api.auth.*;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.community.CommunityService;
import com.storyverse.api.feature.FeatureAccessService;
import com.storyverse.api.rpc.RpcService;
import com.storyverse.api.storage.*;

/** Área do admin. Rode só contra um PostgreSQL descartável, nunca produção. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class AdminIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-admin-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.storage.quota-bytes", () -> "2048");
    properties.add("app.storage.quota-objects", () -> "3");
    properties.add("app.auth.require-email-confirmation", () -> "false");
    properties.add("app.public-api-url", () -> "https://api.example.test");
    properties.add("app.rate-limit.persistent", () -> "true");
    // A suíte usa o superusuário postgres para criar/migrar o banco descartável; o guard de
    // runtime é validado pelo Compose/VPS, não por este teste.
    properties.add("app.security.require-runtime-role", () -> "false");
  }
  @Autowired MockMvc mvc;
  @Autowired AuthService auth;
  @Autowired AuthzService authz;
  @Autowired RpcService rpc;
  @Autowired StorageService storage;
  @Autowired StorageAccess storageAccess;
  @Autowired CommunityService community;
  @Autowired FeatureAccessService features;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;

  AuthResponse user() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Admin")); }
  UUID id(AuthResponse session) { return UUID.fromString(session.user().id().toString()); }
  AuthResponse userWithRole(String role) {
    AuthResponse session=user();
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,:role)").param("id",id(session)).param("role",role).update();
    return session;
  }
  UUID withRole(String role) { return id(userWithRole(role)); }
  /** Com require-email-confirmation=false o signup já nasce confirmado; isto desfaz. */
  void unconfirmEmail(UUID uid) {
    jdbc.sql("update auth.users set email_confirmed_at=null where id=:id").param("id",uid).update();
  }
  int status(Executable action) { return assertThrows(AuthException.class, action).status(); }
  /** Roda como o papel de runtime (storyverse_api, com RLS) e com auth.uid() = uid. */
  <T> T asRuntime(UUID uid, Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(status -> {
      jdbc.sql("set local role storyverse_api").update();
      DbSession.setUser(jdbc, uid);
      return work.get();
    });
  }
  /** Autor cria um envio pendente; devolve {autor, caminho, id}. */
  record Submission(AuthResponse author, String path, UUID id) {}
  Submission pendingSubmission() {
    AuthResponse author=user(); String path=id(author)+"/"+UUID.randomUUID()+".txt";
    storage.write(id(author),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","pendente".getBytes());
    var row=community.create(author.accessToken(),Map.of("title","Livro","text_path",path,"rights","own_work"));
    return new Submission(author,path,UUID.fromString(row.get("id").toString()));
  }

  @Test void legacyAdminsRowGrantsNothing() {
    UUID uid=id(user());
    jdbc.sql("insert into public.admins(user_id) values(:id)").param("id",uid).update();
    assertFalse(authz.isStaff(uid));
    assertEquals(Boolean.FALSE, rpc.call(uid,"is_admin",Map.of()));
  }
  @Test void moderatorReviewsAndReadsPendingSubmission() {
    Submission submission=pendingSubmission();
    AuthResponse moderator=userWithRole("moderator");
    assertTrue(storage.readFor(id(moderator),StorageBucket.COMMUNITY_BOOKS,submission.path()).isPresent());
    assertDoesNotThrow(()->community.review(moderator.accessToken(),submission.id(),Map.of("status","approved")));
    assertEquals("approved",jdbc.sql("select status from public.book_submissions where id=:id").param("id",submission.id()).query(String.class).single());
  }
  @Test void supportCannotReviewSubmissions() {
    Submission submission=pendingSubmission();
    AuthResponse support=userWithRole("support");
    assertEquals(403,status(()->community.review(support.accessToken(),submission.id(),Map.of("status","approved"))));
    assertThrows(AuthException.class,()->storage.readFor(id(support),StorageBucket.COMMUNITY_BOOKS,submission.path()));
  }
  @Test void moderatorSeesOthersPendingUnderRls() {
    Submission submission=pendingSubmission();
    UUID moderator=withRole("moderator"), plain=id(user());
    Supplier<Integer> count=()->jdbc.sql("select count(*) from public.book_submissions where status='pending' and user_id=:author")
        .param("author",id(submission.author())).query(Integer.class).single();
    assertEquals(1,asRuntime(moderator,count));
    assertEquals(0,asRuntime(plain,count));
  }
  @Test void moderatorCannotDeleteOthersProfileMedia() {
    UUID author=id(user()), moderator=withRole("moderator"), superadmin=withRole("superadmin");
    String path=author+"/livros/x.png";
    assertFalse(asRuntime(moderator,()->storageAccess.deletable(moderator,StorageBucket.PROFILE_MEDIA,path)));
    assertTrue(asRuntime(superadmin,()->storageAccess.deletable(superadmin,StorageBucket.PROFILE_MEDIA,path)));
  }

  int audits(String action, Object target) {
    return jdbc.sql("select count(*) from public.admin_audit_log where action=:action and target_id=:target")
        .param("action",action).param("target",target.toString()).query(Integer.class).single();
  }
  static final byte[] PNG={(byte)0x89,'P','N','G',0x0D,0x0A,0x1A,0x0A,0};
  String coverUrl(String path) { return "https://api.example.test/api/v1/storage/public/profile-media/"+path; }

  @Test void featureGrantRollsBackWhenAuditFails() {
    UUID admin=withRole("superadmin"), target=id(user());
    jdbc.sql("alter table public.admin_audit_log add constraint it_fail check (action <> 'feature.set') not valid").update();
    try {
      assertThrows(RuntimeException.class, () -> features.grant(admin,target,"library",true));
      assertTrue(features.features(admin,target).isEmpty());
    } finally {
      jdbc.sql("alter table public.admin_audit_log drop constraint it_fail").update();
    }
  }
  @Test void featureGrantIsAudited() {
    UUID admin=withRole("superadmin"), target=id(user());
    features.grant(admin,target,"library",true);
    assertEquals(List.of("library"),features.features(admin,target));
    assertEquals(1,audits("feature.set",target));
  }
  @Test void moderatorDeletesApprovedSubmissionWithUploadedCover() {
    AuthResponse author=user(); UUID authorId=id(author);
    String textPath=authorId+"/"+UUID.randomUUID()+".txt", coverPath=authorId+"/livros/c.png";
    storage.write(authorId,StorageBucket.COMMUNITY_BOOKS,textPath,"text/plain","aprovado".getBytes());
    storage.write(authorId,StorageBucket.PROFILE_MEDIA,coverPath,"image/png",PNG);
    var row=community.create(author.accessToken(),Map.of("title","Livro","text_path",textPath,"rights","own_work","cover_url",coverUrl(coverPath)));
    UUID submission=UUID.fromString(row.get("id").toString());
    AuthResponse moderator=userWithRole("moderator");
    community.review(moderator.accessToken(),submission,Map.of("status","approved"));
    assertDoesNotThrow(()->community.delete(moderator.accessToken(),submission));
    assertEquals(0,jdbc.sql("select count(*) from public.book_submissions where id=:id").param("id",submission).query(Integer.class).single());
    assertEquals(1,jdbc.sql("select count(*) from public.admin_audit_log where action='book_submission.delete' and target_id=:id and actor_id=:actor")
        .param("id",submission.toString()).param("actor",id(moderator)).query(Integer.class).single());
    assertFalse(storage.exists(StorageBucket.COMMUNITY_BOOKS,textPath));
    assertFalse(storage.exists(StorageBucket.PROFILE_MEDIA,coverPath));
  }
  @Test void ownerDeletingOwnPendingIsNotAudited() {
    Submission submission=pendingSubmission();
    community.delete(submission.author().accessToken(),submission.id());
    assertEquals(0,jdbc.sql("select count(*) from public.admin_audit_log where target_id=:id").param("id",submission.id().toString()).query(Integer.class).single());
    assertFalse(storage.exists(StorageBucket.COMMUNITY_BOOKS,submission.path()));
  }
  @Test void moderatorCoverChangeIsAuditedButOwnerIsNot() {
    Submission submission=pendingSubmission();
    String ownerCover=coverUrl(id(submission.author())+"/livros/a.png");
    community.updateCover(submission.author().accessToken(),submission.id(),Map.of("cover_url",ownerCover));
    assertEquals(0,audits("book_submission.cover",submission.id()));
    AuthResponse moderator=userWithRole("moderator");
    String modCover=coverUrl(id(submission.author())+"/livros/b.png");
    community.updateCover(moderator.accessToken(),submission.id(),Map.of("cover_url",modCover));
    assertEquals(1,audits("book_submission.cover",submission.id()));
    assertEquals(modCover,jdbc.sql("select metadata->>'cover_url' from public.admin_audit_log where action='book_submission.cover' and target_id=:id")
        .param("id",submission.id().toString()).query(String.class).single());
  }
}
