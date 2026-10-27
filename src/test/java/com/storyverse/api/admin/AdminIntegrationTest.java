package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
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
import com.storyverse.api.billing.BillingSeriesPoint;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.catalog.CatalogImportService;
import com.storyverse.api.community.CommunityService;
import com.storyverse.api.feature.FeatureAccessService;
import com.storyverse.api.feature.FeatureUsersPage;
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
  @Autowired SanctionService sanctions;
  @Autowired AdminMetricsService metrics;
  @Autowired CatalogImportService catalogImports;
  @Autowired com.storyverse.api.billing.BillingAdminService billingAdmin;
  @Autowired com.storyverse.api.billing.BillingSnapshotJob snapshotJob;
  @Autowired AdminHealthService health;
  @Autowired AdminTrafficService traffic;
  @Autowired SessionRepository sessions;
  @Autowired TokenService tokens;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;

  AuthResponse user() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Admin")); }
  AuthResponse loginFor(AuthResponse session) { return (AuthResponse) auth.login(new LoginRequest(session.user().email(), "test-secret")); }
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

  // --- Contas: busca e detalhe ---
  @Autowired AdminUserService users;
  /** Conta com e-mail e nome únicos, para a busca não esbarrar em outros testes no banco compartilhado. */
  record Named(AuthResponse session, String email, String name) {}
  Named named() {
    String tag=UUID.randomUUID().toString().replace("-","").substring(0,12);
    String email="conta-"+tag+"@example.invalid", name="Leitora "+tag;
    return new Named((AuthResponse) auth.signup(new SignupRequest(email,"test-secret",name)),email,name);
  }

  @Test void searchByExactEmailMasksTheEmail() {
    Named target=named();
    AuthResponse support=userWithRole("support");
    List<AdminUserSummary> found=users.search(support.accessToken(),"  "+target.email().toUpperCase(Locale.ROOT)+" ");
    assertEquals(1,found.size());
    AdminUserSummary row=found.get(0);
    assertEquals(id(target.session()),row.id());
    assertEquals("co***@example.invalid",row.emailMasked());
    assertFalse(row.emailMasked().contains(target.email()));
    assertTrue(row.emailConfirmed());
    assertEquals(List.of(),row.roles());
  }
  @Test void searchByPartialEmailFindsTheAccount() {
    Named target=named();
    AuthResponse support=userWithRole("support");
    String localPart=target.email().substring(0,target.email().indexOf('@'));
    List<AdminUserSummary> found=users.search(support.accessToken(),localPart);
    assertEquals(List.of(id(target.session())),found.stream().map(AdminUserSummary::id).toList());
    assertEquals(target.name(),found.get(0).name());
    assertFalse(found.get(0).emailMasked().contains(target.email()));
  }
  @Test void searchByNameNeedsThreeChars() {
    Named target=named();
    AuthResponse support=userWithRole("support");
    assertEquals(400,status(()->users.search(support.accessToken(),"ab")));
    assertEquals(400,status(()->users.search(support.accessToken(),null)));
    assertEquals(0,users.search(support.accessToken(),"%%%").size());
    List<AdminUserSummary> found=users.search(support.accessToken(),target.name().substring(8));
    assertEquals(List.of(id(target.session())),found.stream().map(AdminUserSummary::id).toList());
    assertEquals(target.name(),found.get(0).name());
  }
  @Test void searchByUuid() {
    UUID target=withRole("moderator");
    AuthResponse superadmin=userWithRole("superadmin");
    List<AdminUserSummary> found=users.search(superadmin.accessToken(),target.toString());
    assertEquals(1,found.size());
    assertEquals(target,found.get(0).id());
    assertEquals(List.of("moderator"),found.get(0).roles());
  }
  @Test void recentAccountsAreMaskedAndNewestFirst() {
    Named target=named();
    AuthResponse support=userWithRole("support");
    List<AdminUserSummary> rows=users.recent(support.accessToken(),50);
    AdminUserSummary row=rows.stream().filter(r->r.id().equals(id(target.session()))).findFirst().orElseThrow();
    assertTrue(row.emailMasked().contains("***"));
    assertFalse(row.emailMasked().contains(target.email()));
    assertNotNull(row.createdAt());
    assertNotNull(row.roles());
  }
  @Test void listPaginatesWithoutDuplicating() {
    String tag=UUID.randomUUID().toString().replace("-","").substring(0,12);
    AuthResponse support=userWithRole("support");
    AuthResponse first=(AuthResponse)auth.signup(new SignupRequest("a-"+tag+"@example.invalid","test-secret","ZZ "+tag));
    AuthResponse second=(AuthResponse)auth.signup(new SignupRequest("b-"+tag+"@example.invalid","test-secret","ZZ "+tag));
    AdminUserPage page1=users.list(support.accessToken(),"ZZ "+tag,null,null,null,1);
    assertEquals(List.of(id(second)),page1.items().stream().map(AdminUserSummary::id).toList());
    assertNotNull(page1.nextCursor());
    AdminUserPage page2=users.list(support.accessToken(),"ZZ "+tag,null,null,page1.nextCursor(),1);
    assertEquals(List.of(id(first)),page2.items().stream().map(AdminUserSummary::id).toList());
    assertNull(page2.nextCursor());
  }
  @Test void listFiltersByRoleAndFeature() {
    AuthResponse support=userWithRole("support");
    AuthResponse target=userWithRole("support");
    AdminUserPage byRole=users.list(support.accessToken(),id(target).toString(),"support",null,null,20);
    assertEquals(List.of(id(target)),byRole.items().stream().map(AdminUserSummary::id).toList());

    UUID plain=id(user());
    AuthResponse superadmin=userWithRole("superadmin");
    features.grant(id(superadmin),plain,"library",true);
    AdminUserPage byFeature=users.list(support.accessToken(),plain.toString(),null,"library",null,20);
    assertEquals(List.of(plain),byFeature.items().stream().map(AdminUserSummary::id).toList());
    assertTrue(users.list(support.accessToken(),plain.toString(),null,"nope",null,20).items().isEmpty());
  }
  @Test void listRejectsInvalidFiltersAndModerator() {
    AuthResponse support=userWithRole("support");
    assertEquals(400,status(()->users.list(support.accessToken(),"ab",null,null,null,20)));
    assertEquals(400,status(()->users.list(support.accessToken(),null,"chefe",null,null,20)));
    AuthResponse moderator=userWithRole("moderator");
    assertEquals(403,status(()->users.list(moderator.accessToken(),null,null,null,null,20)));
    assertEquals(403,status(()->users.recent(moderator.accessToken(),20)));
  }
  @Test void moderatorCannotSearch() {
    UUID target=id(user());
    AuthResponse moderator=userWithRole("moderator");
    assertEquals(403,status(()->users.search(moderator.accessToken(),target.toString())));
    assertEquals(403,status(()->users.detail(moderator.accessToken(),target)));
  }
  @Test void detailShowsRolesFeaturesSessionsAndAuditOnlyForSuperadmin() {
    Named target=named(); UUID targetId=id(target.session());
    AuthResponse superadmin=userWithRole("superadmin"), support=userWithRole("support");
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'support')").param("id",targetId).update();
    features.grant(id(superadmin),targetId,"library",true);
    AdminUserDetail full=users.detail(superadmin.accessToken(),targetId);
    assertEquals(target.email(),full.email());
    assertEquals(target.name(),full.name());
    assertTrue(full.emailConfirmed());
    assertNotNull(full.createdAt());
    assertEquals(List.of("support"),full.roles());
    assertEquals(List.of("library"),full.features());
    assertTrue(full.activeSessions()>=1);
    assertNotNull(full.recentAudit());
    assertEquals("feature.set",full.recentAudit().get(0).get("action"));
    assertEquals(id(superadmin),full.recentAudit().get(0).get("actorId"));
    AdminUserDetail limited=users.detail(support.accessToken(),targetId);
    assertNull(limited.recentAudit());
    assertTrue(limited.activeSessions()>=1);
  }
  /** O serviço entra na transação do asRuntime (REQUIRED), então o RLS de storyverse_api vale. */
  @Test void supportSeesOthersRolesAndFeaturesUnderRls() {
    Named target=named(); UUID targetId=id(target.session());
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'moderator')").param("id",targetId).update();
    features.grant(withRole("superadmin"),targetId,"library",true);
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator");
    UUID plain=id(user());
    // Prova de que o RLS está ativo: um leitor comum não vê os papéis de outra conta.
    assertEquals(0,asRuntime(plain,()->jdbc.sql("select count(*) from public.user_roles where user_id=:id")
        .param("id",targetId).query(Integer.class).single()));
    AdminUserDetail detail=asRuntime(id(support),()->users.detail(support.accessToken(),targetId));
    assertEquals(List.of("moderator"),detail.roles());
    assertEquals(List.of("library"),detail.features());
    assertNull(detail.recentAudit());
    List<AdminUserSummary> found=asRuntime(id(support),()->users.search(support.accessToken(),targetId.toString()));
    assertEquals(List.of("moderator"),found.get(0).roles());
    assertEquals(403,status(()->asRuntime(id(moderator),()->users.detail(moderator.accessToken(),targetId))));
    assertEquals(403,status(()->asRuntime(id(moderator),()->users.search(moderator.accessToken(),targetId.toString()))));
  }
  @Test void detailIsAudited() {
    UUID target=id(user());
    AuthResponse support=userWithRole("support");
    users.detail(support.accessToken(),target);
    assertEquals(1,audits("user.view",target));
  }
  @Test void detailOfUnknownUserIs404() {
    AuthResponse support=userWithRole("support");
    UUID unknown=UUID.randomUUID();
    assertEquals(404,status(()->users.detail(support.accessToken(),unknown)));
    assertEquals(0,audits("user.view",unknown));
  }

  // --- Contas: encerrar sessões e papéis ---
  List<String> rolesOf(UUID uid) {
    return jdbc.sql("select role from public.user_roles where user_id=:id order by role").param("id",uid).query(String.class).list();
  }
  int auditsWithRole(String action, UUID target, String role) {
    return jdbc.sql("select count(*) from public.admin_audit_log where action=:action and target_id=:target and metadata->>'role'=:role")
        .param("action",action).param("target",target.toString()).param("role",role).query(Integer.class).single();
  }

  @Test void supportRevokesSessions() {
    AuthResponse target=user(); UUID targetId=id(target);
    AuthResponse support=userWithRole("support");
    assertEquals(targetId,auth.authenticate(target.accessToken()));
    Map<String,Object> result=asRuntime(id(support),()->users.revokeSessions(support.accessToken(),targetId));
    assertEquals(1,result.get("revoked"));
    assertEquals(401,status(()->auth.authenticate(target.accessToken())));
    assertEquals(1,jdbc.sql("select count(*) from public.admin_audit_log where action='user.sessions.revoke' and target_id=:t and actor_id=:a and (metadata->>'count')::int=1")
        .param("t",targetId.toString()).param("a",id(support)).query(Integer.class).single());
    assertEquals(id(support),auth.authenticate(support.accessToken()));
  }
  @Test void cannotRevokeOwnSessions() {
    AuthResponse support=userWithRole("support"), superadmin=userWithRole("superadmin");
    assertEquals(403,status(()->asRuntime(id(support),()->users.revokeSessions(support.accessToken(),id(support)))));
    assertEquals(403,status(()->users.revokeSessions(superadmin.accessToken(),id(superadmin))));
    assertEquals(id(support),auth.authenticate(support.accessToken()));
    assertEquals(0,audits("user.sessions.revoke",id(support)));
  }
  @Test void revokeSessionsPermissions() {
    UUID target=id(user());
    AuthResponse moderator=userWithRole("moderator"), plain=user(), support=userWithRole("support");
    assertEquals(403,status(()->asRuntime(id(moderator),()->users.revokeSessions(moderator.accessToken(),target))));
    assertEquals(403,status(()->asRuntime(id(plain),()->users.revokeSessions(plain.accessToken(),target))));
    UUID unknown=UUID.randomUUID();
    assertEquals(404,status(()->asRuntime(id(support),()->users.revokeSessions(support.accessToken(),unknown))));
    assertEquals(0,audits("user.sessions.revoke",target));
  }
  @Test void superadminGrantsModeratorToConfirmedAccount() {
    UUID target=id(user());
    AuthResponse superadmin=userWithRole("superadmin");
    Map<String,Object> granted=asRuntime(id(superadmin),()->users.grantRole(superadmin.accessToken(),target,"moderator"));
    assertEquals(List.of("moderator"),granted.get("roles"));
    assertEquals(List.of("moderator"),rolesOf(target));
    assertEquals(id(superadmin),jdbc.sql("select granted_by from public.user_roles where user_id=:id and role='moderator'").param("id",target).query(UUID.class).single());
    assertEquals(1,auditsWithRole("role.grant",target,"moderator"));
    Map<String,Object> revoked=asRuntime(id(superadmin),()->users.revokeRole(superadmin.accessToken(),target,"moderator"));
    assertEquals(List.of(),revoked.get("roles"));
    assertEquals(List.of(),rolesOf(target));
    assertEquals(1,auditsWithRole("role.revoke",target,"moderator"));
  }
  @Test void grantToUnconfirmedAccountIs409() {
    UUID target=id(user()); unconfirmEmail(target);
    AuthResponse superadmin=userWithRole("superadmin");
    assertEquals(409,status(()->users.grantRole(superadmin.accessToken(),target,"support")));
    assertEquals(List.of(),rolesOf(target));
    assertEquals(0,audits("role.grant",target));
    // Tirar papel de conta sem e-mail confirmado continua possível.
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'support')").param("id",target).update();
    assertEquals(List.of(),users.revokeRole(superadmin.accessToken(),target,"support").get("roles"));
    assertEquals(404,status(()->users.grantRole(superadmin.accessToken(),UUID.randomUUID(),"support")));
  }
  @Test void grantingSuperadminIs400() {
    UUID target=id(user());
    AuthResponse superadmin=userWithRole("superadmin");
    assertEquals(400,status(()->users.grantRole(superadmin.accessToken(),target,"superadmin")));
    assertEquals(400,status(()->users.grantRole(superadmin.accessToken(),target,"admin")));
    assertEquals(List.of(),rolesOf(target));
    UUID otherSuperadmin=withRole("superadmin");
    assertEquals(400,status(()->users.revokeRole(superadmin.accessToken(),otherSuperadmin,"superadmin")));
    assertEquals(List.of("superadmin"),rolesOf(otherSuperadmin));
    // Tirar o próprio superadmin: o papel pedido é checado antes da própria conta.
    assertEquals(400,status(()->users.revokeRole(superadmin.accessToken(),id(superadmin),"superadmin")));
    assertEquals(List.of("superadmin"),rolesOf(id(superadmin)));
    assertEquals(0,audits("role.grant",target)+audits("role.revoke",otherSuperadmin)+audits("role.revoke",id(superadmin)));
  }
  @Test void changingOwnRolesIs403() {
    AuthResponse superadmin=userWithRole("superadmin"); UUID self=id(superadmin);
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'moderator')").param("id",self).update();
    assertEquals(403,status(()->asRuntime(self,()->users.grantRole(superadmin.accessToken(),self,"support"))));
    assertEquals(403,status(()->asRuntime(self,()->users.revokeRole(superadmin.accessToken(),self,"moderator"))));
    assertEquals(List.of("moderator","superadmin"),rolesOf(self));
    assertEquals(0,audits("role.grant",self)+audits("role.revoke",self));
  }
  @Test void supportCannotChangeRoles() {
    UUID target=id(user());
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'moderator')").param("id",target).update();
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator");
    assertEquals(403,status(()->asRuntime(id(support),()->users.grantRole(support.accessToken(),target,"support"))));
    assertEquals(403,status(()->asRuntime(id(support),()->users.revokeRole(support.accessToken(),target,"moderator"))));
    assertEquals(403,status(()->asRuntime(id(moderator),()->users.grantRole(moderator.accessToken(),target,"support"))));
    // O papel do ator vem antes da validação do papel pedido.
    assertEquals(403,status(()->asRuntime(id(support),()->users.grantRole(support.accessToken(),target,"superadmin"))));
    assertEquals(List.of("moderator"),rolesOf(target));
  }
  @Test void idempotentGrantDoesNotAudit() {
    UUID target=id(user());
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'support')").param("id",target).update();
    AuthResponse superadmin=userWithRole("superadmin");
    assertEquals(List.of("support"),asRuntime(id(superadmin),()->users.grantRole(superadmin.accessToken(),target,"support")).get("roles"));
    assertEquals(List.of("support"),users.revokeRole(superadmin.accessToken(),target,"moderator").get("roles"));
    assertEquals(0,audits("role.grant",target)+audits("role.revoke",target));
  }
  @Test void rolesAndSessionsOverHttp() throws Exception {
    UUID target=id(user());
    AuthResponse superadmin=userWithRole("superadmin");
    String bearer="Bearer "+superadmin.accessToken();
    mvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/users/"+target+"/roles/support").header("Authorization",bearer))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.roles[0]").value("support"));
    mvc.perform(MockMvcRequestBuilders.delete("/api/v1/admin/users/"+target+"/roles/support").header("Authorization",bearer))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.roles").isEmpty());
    mvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/users/"+target+"/roles/superadmin").header("Authorization",bearer))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
    mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/users/"+target+"/sessions/revoke").header("Authorization",bearer))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.revoked").value(1));
  }

  // --- San??es ---
  @Test void warningDoesNotBlockAndAppearsOnLogin() {
    AuthResponse target=user(); UUID admin=withRole("superadmin");
    sanctions.apply(admin,id(target),"warning","Leia as regras da comunidade.",Instant.now().plus(Duration.ofDays(7)));
    AuthResponse login=loginFor(target);
    assertEquals(id(target),id(login));
    assertNotNull(login.activeWarning());
    assertEquals("warning",login.activeWarning().kind());
  }
  @Test void suspensionBlocksLoginAndRevokesSessions() {
    AuthResponse target=user(); UUID admin=withRole("superadmin");
    assertEquals(id(target),auth.authenticate(target.accessToken()));
    sanctions.apply(admin,id(target),"suspension","Pagamento em atraso.",Instant.now().plus(Duration.ofDays(3)));
    assertThrows(AccountSanctionedException.class,()->loginFor(target));
    assertEquals(401,status(()->auth.authenticate(target.accessToken())));
  }
  @Test void suspensionBlocksRefreshAndAuthenticatedRequests() {
    AuthResponse target=user(); UUID admin=withRole("superadmin"); UUID targetId=id(target);
    sanctions.apply(admin,targetId,"suspension","Motivo",Instant.now().plus(Duration.ofDays(1)));
    String access=tokens.newToken(), refresh=tokens.newToken();
    sessions.create(targetId,tokens.hash(access),Instant.now().plusSeconds(300),tokens.hash(refresh),Instant.now().plus(Duration.ofDays(1)));
    assertThrows(AccountSanctionedException.class,()->auth.authenticate(access));
    assertThrows(AccountSanctionedException.class,()->auth.refresh(refresh));
  }
  @Test void expiredSuspensionDoesNotBlock() {
    AuthResponse target=user(); UUID targetId=id(target);
    jdbc.sql("""
        insert into public.user_sanctions(user_id,kind,reason,expires_at)
        values (:id,'suspension','Vencida',now()-interval '1 day')
        """).param("id",targetId).update();
    sanctions.invalidate(targetId);
    assertEquals(id(target),id(loginFor(target)));
  }
  @Test void banBlocksUntilRevoked() {
    AuthResponse target=user(); UUID admin=withRole("superadmin");
    Sanction ban=sanctions.apply(admin,id(target),"ban","Descumprimento grave.",null);
    assertThrows(AccountSanctionedException.class,()->loginFor(target));
    Sanction revoked=sanctions.revoke(admin,ban.id(),"Recurso aceito.");
    assertNotNull(revoked.revokedAt());
    assertEquals(id(target),id(loginFor(target)));
    assertEquals(1,audits("sanction.create",id(target)));
    assertEquals(1,audits("sanction.revoke",id(target)));
  }
  @Test void sanctionValidationsPermissionsAndStaffTarget() {
    AuthResponse admin=userWithRole("superadmin"); UUID adminId=id(admin);
    AuthResponse target=user(); UUID targetId=id(target);
    assertEquals(403,status(()->sanctions.apply(adminId,adminId,"warning","x",null)));
    assertEquals(403,status(()->sanctions.apply(adminId,withRole("moderator"),"warning","x",null)));
    assertEquals(400,status(()->sanctions.apply(adminId,targetId,"warning"," ",null)));
    assertEquals(400,status(()->sanctions.apply(adminId,targetId,"suspension","x",null)));
    assertEquals(400,status(()->sanctions.apply(adminId,targetId,"suspension","x",Instant.now().minusSeconds(1))));
    assertEquals(400,status(()->sanctions.apply(adminId,targetId,"ban","x",Instant.now().plusSeconds(60))));
    assertEquals(400,status(()->sanctions.apply(adminId,targetId,"nope","x",null)));
    AuthResponse support=userWithRole("support");
    assertEquals(403,status(()->sanctions.apply(id(support),targetId,"warning","x",null)));
    AuthResponse moderator=userWithRole("moderator");
    assertNotNull(sanctions.list(id(moderator),"active",null,null,null,20));
    assertEquals(403,status(()->sanctions.apply(id(moderator),targetId,"warning","x",null)));
  }
  @Test void historyAndListMaskEmailsAndPaginate() {
    AuthResponse admin=userWithRole("superadmin"); UUID adminId=id(admin);
    AuthResponse first=user(), second=user();
    sanctions.apply(adminId,id(first),"warning","Aviso A",null);
    sanctions.apply(adminId,id(second),"suspension","Suspens?o B",Instant.now().plus(Duration.ofDays(2)));
    List<Sanction> history=sanctions.history(adminId,id(first),20);
    assertEquals(1,history.size());
    assertEquals("warning",history.get(0).kind());
    assertTrue(history.get(0).emailMasked().contains("***"));
    SanctionPage page1=sanctions.list(adminId,"active",null,null,null,1);
    assertEquals(1,page1.items().size());
    assertNotNull(page1.nextCursor());
    SanctionPage page2=sanctions.list(adminId,"active",null,null,page1.nextCursor(),1);
    assertEquals(1,page2.items().size());
    assertNotEquals(page1.items().get(0).id(),page2.items().get(0).id());
    assertTrue(page2.items().get(0).emailMasked().contains("***"));
  }
  @Test void suspendedLoginReturnsCodeAndReason() throws Exception {
    AuthResponse target=user(); UUID admin=withRole("superadmin");
    sanctions.apply(admin,id(target),"suspension","Pagamento em atraso.",Instant.now().plus(Duration.ofDays(3)));
    mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\""+target.user().email()+"\",\"password\":\"test-secret\"}"))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("ACCOUNT_SANCTIONED"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.kind").value("suspension"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.reason").value("Pagamento em atraso."))
        .andExpect(MockMvcResultMatchers.jsonPath("$.expiresAt").exists());
  }

  // --- M?tricas (2C) ---
  @Test void metricsRequireSuperadmin() {
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator");
    assertEquals(403,status(()->metrics.overview(support.accessToken(),null,null)));
    assertEquals(403,status(()->metrics.readingSeries(moderator.accessToken(),null,null,"day")));
    assertEquals(403,status(()->metrics.retention(support.accessToken(),null,null)));
  }
  @Test void metricsOverviewReadingAndRetentionWork() {
    AuthResponse admin=userWithRole("superadmin"); UUID target=id(user());
    jdbc.sql("""
        insert into public.reading_days(user_id,day,seconds)
        values (:id, current_date - 1, 600)
        on conflict (user_id,day) do update set seconds=600
        """).param("id",target).update();
    String from=LocalDate.now().minusDays(7).toString(), to=LocalDate.now().toString();
    AdminMetrics overview=metrics.overview(admin.accessToken(),from,to);
    assertTrue(overview.accounts().total()>0);
    assertTrue(overview.accounts().confirmed()>0);
    assertNotNull(overview.features());
    assertNotNull(overview.retention());
    List<AdminMetricsReadingPoint> days=metrics.readingSeries(admin.accessToken(),from,to,"day");
    assertFalse(days.isEmpty());
    assertTrue(days.stream().allMatch(p->p.activeReaders()>=0 && p.minutes()>=0));
    List<AdminMetricsReadingPoint> weeks=metrics.readingSeries(admin.accessToken(),from,to,"week");
    assertFalse(weeks.isEmpty());
    assertNotNull(metrics.retention(admin.accessToken(),from,to));
    AdminMetrics runtime=asRuntime(id(admin),()->metrics.overview(admin.accessToken(),LocalDate.now().minusDays(8).toString(),to));
    assertNotNull(runtime);
  }
  @Test void metricsRejectInvalidRange() {
    AuthResponse admin=userWithRole("superadmin");
    assertEquals(400,status(()->metrics.overview(admin.accessToken(),"2026-01-02","2026-01-01")));
    assertEquals(400,status(()->metrics.overview(admin.accessToken(),"2026-01-01","2026-05-01")));
    assertEquals(400,status(()->metrics.overview(admin.accessToken(),"lixo",null)));
    assertEquals(400,status(()->metrics.readingSeries(admin.accessToken(),null,null,"year")));
  }
  @Test void metricsCountSignupsInTheLast24hTodayAndPerBucket() {
    AuthResponse admin=userWithRole("superadmin"); user();
    LocalDate today=LocalDate.now(AdminRange.SAO_PAULO);
    AdminMetrics overview=metrics.overview(admin.accessToken(),today.minusDays(1).toString(),today.toString());
    assertTrue(overview.accounts().new24h()>=2, "as duas contas acabaram de ser criadas: "+overview.accounts());
    assertTrue(overview.accounts().newToday()>=2, "hoje em São Paulo: "+overview.accounts());
    assertTrue(overview.accounts().new7()>=overview.accounts().new24h());
    List<AdminMetricsReadingPoint> days=metrics.readingSeries(admin.accessToken(),today.minusDays(2).toString(),today.toString(),"day");
    assertTrue(days.get(days.size()-1).signups()>=2, "o último dia é hoje: "+days);
    List<AdminMetricsReadingPoint> weeks=metrics.readingSeries(admin.accessToken(),today.minusDays(2).toString(),today.toString(),"week");
    assertTrue(weeks.get(weeks.size()-1).signups()>=2, "a semana corrente conta as contas novas: "+weeks);
  }

  // --- Atividade da comunidade (2.6) ---
  /** Insere no fórum com a hora de São Paulo (AAAA-MM-DD HH:MM); devolve o id. */
  UUID forumTopic(UUID author, String created, String status) {
    return jdbc.sql("""
        insert into public.forum_topics(space_id,author_id,title,body,status,deleted_at,created_at)
        values ((select id from public.forum_spaces where slug='duvidas'),:author,'Tópico','corpo',:status,
          case when :status='deleted' then now() end,
          cast(:created as text)::timestamp at time zone 'America/Sao_Paulo')
        returning id
        """).param("author",author).param("status",status).param("created",created).query(UUID.class).single();
  }
  @Test void communityMetricsRequireSuperadminAndValidRange() {
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator"), admin=userWithRole("superadmin");
    assertEquals(403,status(()->metrics.communitySeries(support.accessToken(),null,null,"day")));
    assertEquals(403,status(()->metrics.communitySeries(moderator.accessToken(),null,null,"day")));
    assertEquals(400,status(()->metrics.communitySeries(admin.accessToken(),"2026-01-01","2026-05-01","day")));
    assertEquals(400,status(()->metrics.communitySeries(admin.accessToken(),null,null,"year")));
  }
  @Test void communityMetricsCountPerBucketIncludingModeratedItems() {
    AuthResponse admin=userWithRole("superadmin"); UUID author=id(user()), reporter=id(user());
    // 2024-02-05 é segunda-feira. 23:30 em São Paulo já é o dia seguinte em UTC.
    UUID first=forumTopic(author,"2024-02-05 23:30","published");
    forumTopic(author,"2024-02-06 00:10","hidden");
    forumTopic(author,"2024-02-06 09:00","deleted");
    jdbc.sql("""
        insert into public.forum_replies(topic_id,author_id,body,status,created_at)
        values (:topic,:author,'resposta','hidden','2024-02-06 10:00'::timestamp at time zone 'America/Sao_Paulo')
        """).param("topic",first).param("author",author).update();
    jdbc.sql("""
        insert into public.forum_reports(reporter_id,target_type,target_id,reason_code,status,created_at)
        values (:reporter,'topic',:topic,'spam','actioned','2024-02-07 12:00'::timestamp at time zone 'America/Sao_Paulo')
        """).param("reporter",reporter).param("topic",first).update();
    // Fora do período: 2024-02-12 00:00 em São Paulo.
    forumTopic(author,"2024-02-12 00:00","published");

    List<AdminMetricsCommunityPoint> days=asRuntime(id(admin),
        ()->metrics.communitySeries(admin.accessToken(),"2024-02-05","2024-02-11","day"));
    assertEquals(7,days.size(),"um ponto por dia, vazios com zero: "+days);
    assertEquals(new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-05"),1,0,0),days.get(0));
    assertEquals(new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-06"),2,1,0),days.get(1));
    assertEquals(new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-07"),0,0,1),days.get(2));
    assertEquals(new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-11"),0,0,0),days.get(6));

    List<AdminMetricsCommunityPoint> weeks=metrics.communitySeries(admin.accessToken(),"2024-02-05","2024-02-12","week");
    assertEquals(List.of(
        new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-05"),3,1,1),
        new AdminMetricsCommunityPoint(LocalDate.parse("2024-02-12"),1,0,0)),weeks);
  }
  @Test void communityMetricsOverHttp() throws Exception {
    AuthResponse admin=userWithRole("superadmin");
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/metrics/community")
            .param("from","2023-01-02").param("to","2023-01-03").param("group","day")
            .header("Authorization","Bearer "+admin.accessToken()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$[0].bucket").value("2023-01-02"))
        .andExpect(MockMvcResultMatchers.jsonPath("$[1].topics").value(0))
        .andExpect(MockMvcResultMatchers.jsonPath("$[1].replies").value(0))
        .andExpect(MockMvcResultMatchers.jsonPath("$[1].reports").value(0));
    AuthResponse support=userWithRole("support");
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/metrics/community")
            .header("Authorization","Bearer "+support.accessToken()))
        .andExpect(MockMvcResultMatchers.status().isForbidden());
  }

  // --- Financeiro no tempo (série e conversão) ---
  /** Insere uma assinatura com datas de São Paulo (AAAA-MM-DD HH:MM). */
  void subscription(String stripeId, UUID owner, String status, String created, String trialEnd, String canceled) {
    Map<String,Object> params=new HashMap<>();
    params.put("user",owner); params.put("id",stripeId); params.put("status",status);
    params.put("created",created); params.put("trial",trialEnd); params.put("canceled",canceled);
    var spec=jdbc.sql("""
        insert into public.billing_subscriptions
          (user_id,stripe_subscription_id,stripe_price_id,status,amount_cents,currency,created_at,trial_end,canceled_at,ended_at)
        values (:user,:id,'price_test',:status,990,'brl',
          cast(:created as text)::timestamp at time zone 'America/Sao_Paulo',
          cast(:trial as text)::timestamp at time zone 'America/Sao_Paulo',
          cast(:canceled as text)::timestamp at time zone 'America/Sao_Paulo',
          cast(:canceled as text)::timestamp at time zone 'America/Sao_Paulo')
        """);
    for (var entry : params.entrySet()) spec=spec.param(entry.getKey(),entry.getValue());
    spec.update();
  }
  @Test void billingSeriesUsesSnapshotsEstimatesAndPaidInvoices() {
    AuthResponse admin=userWithRole("superadmin"); UUID owner=id(user());
    String tag="sub_it_"+UUID.randomUUID();
    try {
      // A: paga desde 02/03. B: teste até 10/03, cancelada em 12/03. C: teste até 11/03 e convertida.
      subscription(tag+"_a",owner,"active","2020-03-02 10:00",null,null);
      subscription(tag+"_b",owner,"canceled","2020-03-03 10:00","2020-03-10 10:00","2020-03-12 12:00");
      subscription(tag+"_c",owner,"active","2020-03-04 10:00","2020-03-11 10:00",null);
      jdbc.sql("""
          insert into public.billing_invoices (stripe_invoice_id,stripe_subscription_id,user_id,amount_paid_cents,currency,paid_at)
          values (:id,:sub,:user,500,'brl',timestamp '2020-03-05 15:00' at time zone 'America/Sao_Paulo')
          """).param("id",tag+"_in").param("sub",tag+"_a").param("user",owner).update();
      jdbc.sql("insert into public.billing_daily_snapshots (day,mrr_cents,active,trialing,past_due) values (date '2020-03-04',12345,7,0,0)").update();

      List<BillingSeriesPoint> days=billingAdmin.series(admin.accessToken(),"2020-03-01","2020-03-14","day");
      assertEquals(14,days.size());
      Map<LocalDate,BillingSeriesPoint> byDay=new HashMap<>();
      days.forEach(p->byDay.put(p.bucket(),p));
      BillingSeriesPoint mar1=byDay.get(LocalDate.parse("2020-03-01"));
      assertEquals(0,mar1.mrrCents()); assertEquals(0,mar1.active()); assertTrue(mar1.estimated());
      BillingSeriesPoint mar2=byDay.get(LocalDate.parse("2020-03-02"));
      assertEquals(990,mar2.mrrCents()); assertEquals(1,mar2.active()); assertEquals(1,mar2.newCount());
      BillingSeriesPoint mar4=byDay.get(LocalDate.parse("2020-03-04"));
      assertEquals(12345,mar4.mrrCents(),"dia com snapshot usa o snapshot"); assertEquals(7,mar4.active()); assertFalse(mar4.estimated());
      assertEquals(500,byDay.get(LocalDate.parse("2020-03-05")).revenueCents());
      assertEquals(2970,byDay.get(LocalDate.parse("2020-03-11")).mrrCents(),"testes de B e C já acabaram");
      BillingSeriesPoint mar12=byDay.get(LocalDate.parse("2020-03-12"));
      assertEquals(1980,mar12.mrrCents(),"B encerrou no dia"); assertEquals(2,mar12.active()); assertEquals(1,mar12.canceledCount());

      List<BillingSeriesPoint> weeks=billingAdmin.series(admin.accessToken(),"2020-03-01","2020-03-14","week");
      assertEquals(List.of(LocalDate.parse("2020-02-24"),LocalDate.parse("2020-03-02"),LocalDate.parse("2020-03-09")),
          weeks.stream().map(BillingSeriesPoint::bucket).toList());
      assertEquals(3,weeks.get(1).newCount()); assertEquals(500,weeks.get(1).revenueCents());
      assertEquals(990,weeks.get(1).mrrCents(),"fecha no domingo 08/03, com B e C ainda em teste");
      assertEquals(1980,weeks.get(2).mrrCents()); assertEquals(1,weeks.get(2).canceledCount());

      Map<String,Object> summary=billingAdmin.summary(admin.accessToken(),"2020-03-01","2020-03-15");
      assertEquals(2L,((Number)summary.get("trials_started")).longValue(),"B e C começaram com teste: "+summary);
      assertEquals(2L,((Number)summary.get("trials_ended")).longValue());
      assertEquals(1L,((Number)summary.get("trials_converted")).longValue(),"só C virou paga");

      List<BillingSeriesPoint> runtime=asRuntime(id(admin),()->billingAdmin.series(admin.accessToken(),"2020-03-01","2020-03-07","day"));
      assertEquals(7,runtime.size());
    } finally {
      jdbc.sql("delete from public.billing_invoices where stripe_invoice_id like :tag").param("tag",tag+"%").update();
      jdbc.sql("delete from public.billing_subscriptions where stripe_subscription_id like :tag").param("tag",tag+"%").update();
      jdbc.sql("delete from public.billing_daily_snapshots where day=date '2020-03-04'").update();
    }
  }
  @Test void billingSeriesValidatesAccessAndRange() {
    AuthResponse admin=userWithRole("superadmin"), support=userWithRole("support");
    assertEquals(403,status(()->billingAdmin.series(support.accessToken(),null,null,"day")));
    assertEquals(400,status(()->billingAdmin.series(admin.accessToken(),"2026-01-01","2026-05-01","day")));
    assertEquals(400,status(()->billingAdmin.series(admin.accessToken(),null,null,"month")));
    List<BillingSeriesPoint> last30=billingAdmin.series(admin.accessToken(),null,null,null);
    assertEquals(30,last30.size());
    assertFalse(last30.get(29).estimated(),"hoje vem do estado atual, não de estimativa");
  }
  @Test void billingSnapshotJobWritesTodayIdempotently() {
    LocalDate today=LocalDate.now(AdminRange.SAO_PAULO);
    snapshotJob.snapshot();
    snapshotJob.snapshot();
    assertEquals(1,jdbc.sql("select count(*) from public.billing_daily_snapshots where day=:d")
        .param("d",java.sql.Date.valueOf(today)).query(Integer.class).single());
  }

  // --- Tráfego (Umami) ---
  @Test void trafficWithoutUmamiConfigStillAnswers() {
    AuthResponse admin=userWithRole("superadmin"), support=userWithRole("support");
    assertEquals(403,status(()->traffic.traffic(support.accessToken(),null,null)));
    assertEquals(400,status(()->traffic.traffic(admin.accessToken(),"2026-01-01","2026-05-01")));
    AdminTraffic result=traffic.traffic(admin.accessToken(),"2026-10-01","2026-10-07");
    assertEquals(LocalDate.parse("2026-10-01"),result.from());
    assertNull(result.traffic());
    assertEquals("Umami não configurado.",result.error());
  }

  // --- Saúde (Sentry) ---
  @Test void healthWithoutSentryConfigStillAnswers() {
    AuthResponse admin=userWithRole("superadmin"), support=userWithRole("support");
    assertEquals(403,status(()->health.health(support.accessToken(),null)));
    assertEquals(400,status(()->health.health(admin.accessToken(),"30d")));
    AdminHealth result=health.health(admin.accessToken(),null);
    assertEquals("14d",result.period());
    assertNull(result.sentry());
    assertEquals("Sentry não configurado.",result.error());
    assertNotNull(result.backup());
  }

  // --- Features: catálogo e quem tem cada uma ---
  String body(String bearer, String url, boolean runtime, UUID actor, int expected) {
    Supplier<String> call=()->{
      try {
        return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization",bearer))
            .andExpect(MockMvcResultMatchers.status().is(expected)).andReturn().getResponse().getContentAsString();
      } catch (Exception e) { throw new RuntimeException(e); }
    };
    if (!runtime) return call.get();
    // Resposta de erro marca a transação do asRuntime como rollback-only: o status já foi checado dentro dela.
    java.util.concurrent.atomic.AtomicReference<String> out=new java.util.concurrent.atomic.AtomicReference<>();
    try { asRuntime(actor,()->{ out.set(call.get()); return null; }); }
    catch (org.springframework.transaction.UnexpectedRollbackException e) { if (expected==200) throw e; }
    return out.get();
  }
  long libraryCount(String bearer, boolean runtime, UUID actor) throws Exception {
    String json=body(bearer,"/api/v1/admin/features/catalog",runtime,actor,200);
    return ((Number) ((List<?>) new tools.jackson.databind.json.JsonMapper().readValue(json,List.class)).stream()
        .map(o->(Map<?,?>) o).filter(m->"library".equals(m.get("feature"))).findFirst().orElseThrow().get("enabledCount")).longValue();
  }
  @Test void catalogCountsEnabledOnly() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String bearer="Bearer "+superadmin.accessToken();
    long before=libraryCount(bearer,false,admin);
    assertEquals(before,libraryCount(bearer,true,admin));
    features.grant(admin,id(user()),"library",true);
    features.grant(admin,id(user()),"library",false);
    assertEquals(before+1,libraryCount(bearer,false,admin));
    assertEquals(before+1,libraryCount(bearer,true,admin));
    assertEquals(Set.of("library"),FeatureAccessService.known());
    assertEquals(before+1,features.catalog(admin).stream().filter(e->e.feature().equals("library")).findFirst().orElseThrow().enabledCount());
  }
  @Test void featureUsersPaginates() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String bearer="Bearer "+superadmin.accessToken();
    Set<UUID> mine=new HashSet<>();
    for (int i=0;i<55;i++) { UUID uid=id(user()); mine.add(uid); features.grant(admin,uid,"library",true); }
    UUID disabled=id(user()); features.grant(admin,disabled,"library",false);
    Map<UUID,Integer> seen=new HashMap<>();
    String cursor=null; int page=0;
    do {
      String url="/api/v1/admin/features/library/users"+(cursor==null?"":"?cursor="+cursor);
      String json=body(bearer,url,page%2==1,admin,200);
      Map<?,?> parsed=new tools.jackson.databind.json.JsonMapper().readValue(json,Map.class);
      List<?> items=(List<?>) parsed.get("items");
      assertTrue(items.size()<=50);
      if (page==0) assertEquals(50,items.size());
      for (Object o : items) {
        Map<?,?> m=(Map<?,?>) o;
        UUID uid=UUID.fromString(m.get("userId").toString());
        seen.merge(uid,1,Integer::sum);
        assertTrue(m.get("emailMasked").toString().contains("***"));
        assertFalse(m.get("emailMasked").toString().contains("@example.invalid") && m.get("emailMasked").toString().length()>30);
        assertNotNull(m.get("name")); assertNotNull(m.get("updatedAt"));
      }
      cursor=(String) parsed.get("nextCursor");
      if (cursor!=null) assertEquals(50,items.size());
      page++;
    } while (cursor!=null && page<1000);
    assertTrue(seen.values().stream().allMatch(n->n==1));
    assertTrue(seen.keySet().containsAll(mine));
    assertFalse(seen.containsKey(disabled));
    FeatureUsersPage first=features.usersWith(admin,"library",null);
    assertEquals(50,first.items().size());
    assertNotNull(first.nextCursor());
    assertEquals("Admin",first.items().stream().filter(r->mine.contains(r.userId())).findFirst().orElseThrow().grantedByName());
  }
  @Test void unknownFeatureIs400() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String bearer="Bearer "+superadmin.accessToken();
    body(bearer,"/api/v1/admin/features/nope/users",false,admin,400);
    body(bearer,"/api/v1/admin/features/nope/users",true,admin,400);
    assertEquals(400,status(()->features.usersWith(admin,"nope",null)));
    assertEquals(400,status(()->features.usersWith(admin,"library","lixo")));
  }
  @Test void supportCannotSeeFeatures() throws Exception {
    AuthResponse support=userWithRole("support"), moderator=userWithRole("moderator"), plain=user();
    for (AuthResponse who : List.of(support,moderator,plain)) {
      String bearer="Bearer "+who.accessToken();
      body(bearer,"/api/v1/admin/features/catalog",false,id(who),403);
      body(bearer,"/api/v1/admin/features/catalog",true,id(who),403);
      body(bearer,"/api/v1/admin/features/library/users",false,id(who),403);
      body(bearer,"/api/v1/admin/features/library/users",true,id(who),403);
    }
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/features/catalog")).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }
  // ---- visão geral ----
  @Autowired AdminOverviewService overview;
  /** Outros testes contam denúncias pendentes no banco inteiro: quem insere aqui apaga depois. */
  UUID insertReport() {
    UUID reporter=id(user());
    return jdbc.sql("insert into public.forum_reports(reporter_id,target_type,target_id,reason_code) values(:r,'topic',gen_random_uuid(),'spam') returning id").param("r",reporter).query(UUID.class).single();
  }
  @Test void moderatorSeesOnlyModeration() throws Exception {
    AuthResponse moderator=userWithRole("moderator"); UUID mid=id(moderator);
    pendingSubmission(); UUID report=insertReport();
    try {
    for (boolean runtime : new boolean[]{false,true}) {
      AdminOverview o=runtime?asRuntime(mid,()->overview.overview(moderator.accessToken())):overview.overview(moderator.accessToken());
      assertNotNull(o.moderation());
      assertEquals(jdbc.sql("select count(*) from public.book_submissions where status='pending'").query(Integer.class).single(),o.moderation().pendingSubmissions());
      assertEquals(jdbc.sql("select count(*) from public.forum_reports where status='pending'").query(Integer.class).single(),o.moderation().openReports());
      assertTrue(o.moderation().pendingSubmissions()>=1);
      assertTrue(o.moderation().openReports()>=1);
      assertNull(o.accounts()); assertNull(o.features()); assertNull(o.backup());
    }
    String json=body("Bearer "+moderator.accessToken(),"/api/v1/admin/overview",true,mid,200);
    Map<?,?> parsed=new tools.jackson.databind.json.JsonMapper().readValue(json,Map.class);
    assertNotNull(parsed.get("moderation")); assertNull(parsed.get("backup"));
    } finally { jdbc.sql("delete from public.forum_reports where id=:id").param("id",report).update(); }
  }
  @Test void supportSeesOnlyAccounts() {
    AuthResponse support=userWithRole("support"); UUID sid=id(support);
    for (boolean runtime : new boolean[]{false,true}) {
      AdminOverview o=runtime?asRuntime(sid,()->overview.overview(support.accessToken())):overview.overview(support.accessToken());
      assertNull(o.moderation()); assertNull(o.features()); assertNull(o.backup());
      assertEquals(jdbc.sql("select count(*) from auth.users").query(Integer.class).single(),o.accounts().total());
      assertTrue(o.accounts().newLast7Days()>=1);
      assertTrue(o.accounts().total()>=o.accounts().newLast7Days());
    }
  }
  @Test void superadminSeesAll() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    features.grant(admin,id(user()),"library",true);
    jdbc.sql("insert into public.backup_runs(started_at,finished_at,status,size_bytes) values(now()-interval '2 minutes',now()-interval '1 minute','success',1234)").update();
    for (boolean runtime : new boolean[]{false,true}) {
      AdminOverview o=runtime?asRuntime(admin,()->overview.overview(superadmin.accessToken())):overview.overview(superadmin.accessToken());
      assertNotNull(o.moderation()); assertNotNull(o.accounts());
      assertTrue(o.features().get("library")>=1);
      assertEquals(features.catalog(admin).stream().filter(e->e.feature().equals("library")).findFirst().orElseThrow().enabledCount(),o.features().get("library").longValue());
      assertNotNull(o.backup());
      assertNotNull(o.backup().status());
    }
    String json=body("Bearer "+superadmin.accessToken(),"/api/v1/admin/overview",true,admin,200);
    Map<?,?> parsed=new tools.jackson.databind.json.JsonMapper().readValue(json,Map.class);
    assertNotNull(parsed.get("moderation")); assertNotNull(parsed.get("accounts"));
    assertNotNull(parsed.get("features")); assertNotNull(((Map<?,?>) parsed.get("backup")).get("stale"));
  }
  @Test void backupIsStaleWithoutRecentSuccess() {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    jdbc.sql("delete from public.backup_runs").update();
    try {
      AdminOverview none=asRuntime(admin,()->overview.overview(superadmin.accessToken()));
      assertNull(none.backup().status()); assertNull(none.backup().finishedAt()); assertNull(none.backup().sizeBytes()); assertTrue(none.backup().stale());
      jdbc.sql("insert into public.backup_runs(started_at,finished_at,status,size_bytes) values(now()-interval '30 hours 1 minute',now()-interval '30 hours','success',10)").update();
      for (boolean runtime : new boolean[]{false,true}) {
        AdminOverview o=runtime?asRuntime(admin,()->overview.overview(superadmin.accessToken())):overview.overview(superadmin.accessToken());
        assertTrue(o.backup().stale()); assertEquals("success",o.backup().status()); assertEquals(10L,o.backup().sizeBytes());
      }
      jdbc.sql("insert into public.backup_runs(started_at,finished_at,status,size_bytes) values(now()-interval '1 hour 1 minute',now()-interval '1 hour','success',20)").update();
      for (boolean runtime : new boolean[]{false,true}) {
        AdminOverview o=runtime?asRuntime(admin,()->overview.overview(superadmin.accessToken())):overview.overview(superadmin.accessToken());
        assertFalse(o.backup().stale()); assertEquals(20L,o.backup().sizeBytes()); assertNotNull(o.backup().finishedAt());
      }
      // Falha mais recente aparece como último, mas o alerta segue o último sucesso.
      jdbc.sql("insert into public.backup_runs(started_at,finished_at,status) values(now()-interval '5 minutes',now()-interval '4 minutes','failed')").update();
      AdminOverview failed=asRuntime(admin,()->overview.overview(superadmin.accessToken()));
      assertEquals("failed",failed.backup().status()); assertFalse(failed.backup().stale());
    } finally {
      jdbc.sql("delete from public.backup_runs").update();
    }
  }
  @Test void plainUserIs403() throws Exception {
    AuthResponse plain=user();
    assertEquals(403,status(()->overview.overview(plain.accessToken())));
    assertEquals(403,status(()->asRuntime(id(plain),()->overview.overview(plain.accessToken()))));
    body("Bearer "+plain.accessToken(),"/api/v1/admin/overview",false,id(plain),403);
    body("Bearer "+plain.accessToken(),"/api/v1/admin/overview",true,id(plain),403);
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/overview")).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }
  // ---- auditoria ----
  @Autowired AdminAuditService auditService;
  AdminAuditService.Filter filter(String action, String actorId, String targetType, String targetId, String from, String to, String before) {
    return new AdminAuditService.Filter(action,actorId,targetType,targetId,from,to,before);
  }
  AdminAuditService.Filter byAction(String action) { return filter(action,null,null,null,null,null,null); }
  void insertAudit(UUID actor, String action, String targetId, String createdAt) {
    jdbc.sql("insert into public.admin_audit_log(actor_id,action,target_type,target_id,metadata,created_at) values(:a,:action,'it',:t,'{\"k\":\"v\"}'::jsonb,cast(:c as timestamptz))")
        .param("a",actor).param("action",action).param("t",targetId).param("c",createdAt).update();
  }
  AdminAuditPage audit(AuthResponse who, boolean runtime, AdminAuditService.Filter f) {
    return runtime?asRuntime(id(who),()->auditService.audit(who.accessToken(),f)):auditService.audit(who.accessToken(),f);
  }
  @Test void auditFiltersByActionPrefix() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String tag="it"+UUID.randomUUID().toString().replace("-","");
    insertAudit(admin,tag+".forum.delete","a","2026-03-01T12:00:00Z");
    insertAudit(admin,tag+".forum.pin","b","2026-03-01T12:00:01Z");
    insertAudit(admin,tag+".user.view","c","2026-03-01T12:00:02Z");
    insertAudit(admin,tag+"Xforum.literal","d","2026-03-01T12:00:03Z");
    for (boolean runtime : new boolean[]{false,true}) {
      AdminAuditPage forum=audit(superadmin,runtime,byAction(tag+".forum."));
      assertEquals(List.of(tag+".forum.pin",tag+".forum.delete"),forum.items().stream().map(AdminAuditPage.Entry::action).toList());
      assertNull(forum.nextBefore());
      assertEquals(admin,forum.items().get(0).actorId());
      assertEquals("Admin",forum.items().get(0).actorName());
      assertEquals("v",forum.items().get(0).metadata().get("k"));
      assertEquals(3,audit(superadmin,runtime,byAction(tag+".")).items().size());
      // '_' e '%' valem como texto, não como curinga
      assertEquals(0,audit(superadmin,runtime,byAction(tag+"_forum")).items().size());
      assertEquals(0,audit(superadmin,runtime,byAction(tag+"%")).items().size());
      assertEquals(1,audit(superadmin,runtime,filter(tag+".",null,null,"b",null,null,null)).items().size());
      assertEquals(3,audit(superadmin,runtime,filter(tag+".",admin.toString(),"it",null,null,null,null)).items().size());
      assertEquals(0,audit(superadmin,runtime,filter(tag+".",UUID.randomUUID().toString(),null,null,null,null,null)).items().size());
    }
    String json=body("Bearer "+superadmin.accessToken(),"/api/v1/admin/audit?action="+tag+".forum.",true,admin,200);
    Map<?,?> parsed=new tools.jackson.databind.json.JsonMapper().readValue(json,Map.class);
    assertEquals(2,((List<?>) parsed.get("items")).size());
    assertTrue(parsed.containsKey("nextBefore")); assertNull(parsed.get("nextBefore"));
    Map<?,?> first=(Map<?,?>) ((List<?>) parsed.get("items")).get(0);
    assertEquals(tag+".forum.pin",first.get("action")); assertEquals("it",first.get("targetType"));
    assertEquals("v",((Map<?,?>) first.get("metadata")).get("k")); assertNotNull(first.get("createdAt"));
  }
  @Test void auditPaginatesByBefore() {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String action="it."+UUID.randomUUID().toString().replace("-","")+".x";
    jdbc.sql("insert into public.admin_audit_log(actor_id,action,target_type,target_id) select :a,:action,'it',g::text from generate_series(1,55) g")
        .param("a",admin).param("action",action).update();
    for (boolean runtime : new boolean[]{false,true}) {
      AdminAuditPage first=audit(superadmin,runtime,byAction(action));
      assertEquals(50,first.items().size());
      assertNotNull(first.nextBefore());
      assertEquals(first.items().get(49).id(),first.nextBefore().longValue());
      for (int i=1;i<first.items().size();i++) assertTrue(first.items().get(i-1).id()>first.items().get(i).id());
      AdminAuditPage second=audit(superadmin,runtime,filter(action,null,null,null,null,null,first.nextBefore().toString()));
      assertEquals(5,second.items().size());
      assertNull(second.nextBefore());
      assertTrue(second.items().get(0).id()<first.nextBefore());
    }
  }
  @Test void auditDateRangeIsInclusiveInSaoPaulo() {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String action="it."+UUID.randomUUID().toString().replace("-","")+".d";
    insertAudit(admin,action,"before","2026-03-09T23:30:00-03:00");
    insertAudit(admin,action,"start","2026-03-10T00:00:00-03:00");
    insertAudit(admin,action,"late","2026-03-10T23:30:00-03:00");
    insertAudit(admin,action,"after","2026-03-11T00:00:00-03:00");
    for (boolean runtime : new boolean[]{false,true}) {
      List<String> day=audit(superadmin,runtime,filter(action,null,null,null,"2026-03-10","2026-03-10",null)).items().stream().map(AdminAuditPage.Entry::targetId).toList();
      assertEquals(List.of("late","start"),day);
      assertEquals(List.of("late","start","before"),audit(superadmin,runtime,filter(action,null,null,null,null,"2026-03-10",null)).items().stream().map(AdminAuditPage.Entry::targetId).toList());
      assertEquals(List.of("after","late","start"),audit(superadmin,runtime,filter(action,null,null,null,"2026-03-10",null,null)).items().stream().map(AdminAuditPage.Entry::targetId).toList());
    }
  }
  @Test void scriptRowsHaveNullActor() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String action="it."+UUID.randomUUID().toString().replace("-","")+".s";
    insertAudit(null,action,"s1","2026-03-01T12:00:00Z");
    for (boolean runtime : new boolean[]{false,true}) {
      AdminAuditPage page=audit(superadmin,runtime,byAction(action));
      assertEquals(1,page.items().size());
      assertNull(page.items().get(0).actorId()); assertNull(page.items().get(0).actorName());
    }
    String json=body("Bearer "+superadmin.accessToken(),"/api/v1/admin/audit?action="+action,true,admin,200);
    Map<?,?> item=(Map<?,?>) ((List<?>) new tools.jackson.databind.json.JsonMapper().readValue(json,Map.class).get("items")).get(0);
    assertTrue(item.containsKey("actorId")); assertNull(item.get("actorId")); assertNull(item.get("actorName"));
  }
  @Test void moderatorCannotReadAudit() throws Exception {
    for (String role : new String[]{"moderator","support"}) {
      AuthResponse who=userWithRole(role); UUID uid=id(who);
      assertEquals(403,status(()->auditService.audit(who.accessToken(),byAction("it."))));
      assertEquals(403,status(()->asRuntime(uid,()->auditService.audit(who.accessToken(),byAction("it.")))));
      body("Bearer "+who.accessToken(),"/api/v1/admin/audit",false,uid,403);
      body("Bearer "+who.accessToken(),"/api/v1/admin/audit",true,uid,403);
    }
    AuthResponse plain=user();
    assertEquals(403,status(()->auditService.audit(plain.accessToken(),byAction("it."))));
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/audit")).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }
  @Test void invalidDateIs400() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    assertEquals(400,status(()->auditService.audit(superadmin.accessToken(),filter(null,null,null,null,"10/03/2026",null,null))));
    assertEquals(400,status(()->auditService.audit(superadmin.accessToken(),filter(null,null,null,null,null,"2026-02-30",null))));
    assertEquals(400,status(()->auditService.audit(superadmin.accessToken(),filter(null,"não-é-uuid",null,null,null,null,null))));
    assertEquals(400,status(()->auditService.audit(superadmin.accessToken(),filter(null,null,null,null,null,null,"abc"))));
    String json=body("Bearer "+superadmin.accessToken(),"/api/v1/admin/audit?from=ontem",false,admin,400);
    assertTrue(json.contains("Data inv"));
  }

  @Test void auditCsvExportRespectsFiltersAndAudits() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin"); UUID admin=id(superadmin);
    String tag="csv"+UUID.randomUUID().toString().replace("-","");
    insertAudit(admin,tag+".x","a","2026-03-01T12:00:00Z");
    insertAudit(admin,tag+".y","b","2026-03-02T12:00:00Z");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    auditService.exportCsv(superadmin.accessToken(),
        filter(tag+".",null,null,null,"2026-03-01","2026-03-31",null),out);
    String csv=out.toString(StandardCharsets.UTF_8);
    assertTrue(csv.startsWith("\uFEFF"));
    assertTrue(csv.contains("id;createdAt;actor;action;targetType;targetId;metadata"));
    assertTrue(csv.contains(tag+".x"));
    assertTrue(csv.contains(tag+".y"));
    assertEquals(1,audits("audit.export","export"));
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/audit/export?from=2026-03-01&to=2026-03-31&action="+tag+".")
        .header("Authorization","Bearer "+superadmin.accessToken()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith("text/csv"))
        .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString(tag+".x")));
  }
  @Test void auditCsvExportRejectsBadPeriodAndPermissions() throws Exception {
    AuthResponse superadmin=userWithRole("superadmin");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    assertEquals(400,status(()->auditService.exportCsv(superadmin.accessToken(),
        filter(null,null,null,null,null,null,null),out)));
    assertEquals(400,status(()->auditService.exportCsv(superadmin.accessToken(),
        filter(null,null,null,null,"2026-01-01","2026-03-01",null),out)));
    AuthResponse support=userWithRole("support");
    assertEquals(403,status(()->auditService.exportCsv(support.accessToken(),
        filter(null,null,null,null,"2026-03-01","2026-03-02",null),out)));
  }

  @Test void catalogBatchImportExecutesUnderRuntimeRole() throws Exception {
    AuthResponse admin=userWithRole("superadmin"); UUID adminId=id(admin);
    String tag=UUID.randomUUID().toString().replace("-","");
    String path=adminId+"/lote-"+tag+".zip";
    ByteArrayOutputStream zipBytes=new ByteArrayOutputStream();
    try (ZipOutputStream zip=new ZipOutputStream(zipBytes)) {
      zip.putNextEntry(new ZipEntry("metadata.csv"));
      zip.write("file,rights\nlivro.txt,public_domain\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("livro.txt"));
      zip.write(("Era uma vez um livro de teste. ".repeat(20)).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    storage.write(adminId, StorageBucket.CATALOG_IMPORTS, path, "application/zip", zipBytes.toByteArray());
    String fileName="lote-"+tag+".zip";
    try {
      Map<String,Object> result=asRuntime(adminId,()->catalogImports.run(admin.accessToken(),
          Map.of("archivePath",path,"fileName",fileName,"dryRun",false)));
      assertEquals(1,((Number)result.get("imported")).intValue());
      assertEquals(0,((Number)result.get("skipped")).intValue());
      assertEquals("livro",jdbc.sql("""
          select title from public.catalog_books
           where source='import' and created_by=:a order by created_at desc limit 1
          """).param("a",adminId).query(String.class).single());
    } finally {
      jdbc.sql("""
          delete from public.catalog_import_items
           where import_id in (select id from public.catalog_imports where file_name=:f)
          """).param("f",fileName).update();
      jdbc.sql("delete from public.catalog_imports where file_name=:f").param("f",fileName).update();
      jdbc.sql("delete from public.catalog_books where source='import' and created_by=:a and title='livro'")
          .param("a",adminId).update();
    }
  }

  /** Lote misto: entra o livro válido, a cópia idêntica é pulada e a linha inválida vira erro. */
  @Test void catalogBatchImportHandlesDuplicatesAndErrors() {
    AuthResponse admin=userWithRole("superadmin"); UUID adminId=id(admin);
    String tag=UUID.randomUUID().toString().replace("-","").substring(0,8);
    String path=adminId+"/lote-misto-"+tag+".zip";
    String fileName="lote-misto-"+tag+".zip";
    String texto="Texto do livro de teste do lote misto. ".repeat(12);
    ByteArrayOutputStream zipBytes=new ByteArrayOutputStream();
    try (ZipOutputStream zip=new ZipOutputStream(zipBytes)) {
      zip.putNextEntry(new ZipEntry("metadata.csv"));
      zip.write(("file,title,rights\n"
          +"um.txt,Livro um "+tag+",public_domain\n"
          +"um-copia.txt,Copia "+tag+",public_domain\n"
          +"sumido.txt,Sumido "+tag+",public_domain\n"
          +"direitos.txt,Direitos "+tag+",copyrighted\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (String nome : List.of("um.txt","um-copia.txt")) {
        zip.putNextEntry(new ZipEntry(nome));
        zip.write(texto.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("direitos.txt"));
      zip.write("Outro texto, com direitos invalidos.".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (Exception e) { throw new RuntimeException(e); }
    storage.write(adminId, StorageBucket.CATALOG_IMPORTS, path, "application/zip", zipBytes.toByteArray());
    try {
      Map<String,Object> result=asRuntime(adminId,()->catalogImports.run(admin.accessToken(),
          Map.of("archivePath",path,"fileName",fileName,"dryRun",false)));
      assertEquals(1,((Number)result.get("imported")).intValue(),"um livro importado: "+result);
      assertEquals(1,((Number)result.get("skipped")).intValue(),"a cópia idêntica é pulada: "+result);
      List<?> items=(List<?>)result.get("items");
      long erros=items.stream().filter(i->"error".equals(((Map<?,?>)i).get("status"))).count();
      assertEquals(2,erros,"arquivo ausente e direitos inválidos: "+items);
    } finally {
      jdbc.sql("""
          delete from public.catalog_import_items
           where import_id in (select id from public.catalog_imports where file_name=:f)
          """).param("f",fileName).update();
      jdbc.sql("delete from public.catalog_imports where file_name=:f").param("f",fileName).update();
      jdbc.sql("delete from public.catalog_books where source='import' and created_by=:a")
          .param("a",adminId).update();
    }
  }

  /** O resumo do financeiro grava auditoria: precisa de transação de escrita, não readOnly. */
  @Test void billingSummaryWorksUnderRuntimeRole() {
    AuthResponse admin=userWithRole("superadmin"); UUID adminId=id(admin);
    Map<String,Object> summary=asRuntime(adminId,() -> billingAdmin.summary(admin.accessToken(), null, null));
    assertTrue(summary.containsKey("from"), "resumo traz o período: "+summary);
    assertTrue(summary.containsKey("to"), "resumo traz o período: "+summary);
  }

  /** A coleção é curadoria: a ordem salva no painel manda na listagem pública da coleção. */
  @Test void catalogCollectionOrderWinsOnPublicListing() throws Exception {
    String tag=UUID.randomUUID().toString().replace("-","").substring(0,8);
    UUID collection=UUID.randomUUID(), older=UUID.randomUUID(), newer=UUID.randomUUID();
    String slug="ordem-"+tag;
    OffsetDateTime base=OffsetDateTime.now().withNano(0);
    jdbc.sql("insert into public.catalog_collections (id,slug,title,status) values (:id,:slug,:title,'published')")
        .param("id",collection).param("slug",slug).param("title","Ordem "+tag).update();
    // "Zeta" é o mais antigo e o último em ordem alfabética: assim a ordem da coleção, a de
    // publicação e a de título são todas diferentes.
    insertPublicBook(older,"cat:"+older,"Zeta "+tag,base.minusDays(2));
    insertPublicBook(newer,"cat:"+newer,"Alfa "+tag,base);
    jdbc.sql("insert into public.catalog_collection_books (collection_id,book_id,sort_order) values (:c,:b,:o)")
        .param("c",collection).param("b",older).param("o",1).update();
    jdbc.sql("insert into public.catalog_collection_books (collection_id,book_id,sort_order) values (:c,:b,:o)")
        .param("c",collection).param("b",newer).param("o",2).update();
    try {
      String byCollection=publicListJson(slug,null);
      assertTrue(byCollection.indexOf("Zeta "+tag) < byCollection.indexOf("Alfa "+tag),
          "a ordem da coleção manda na listagem: "+byCollection);
      String byTitle=publicListJson(slug,"title");
      assertTrue(byTitle.indexOf("Alfa "+tag) < byTitle.indexOf("Zeta "+tag),
          "título continua mandando quando é pedido: "+byTitle);
    } finally {
      jdbc.sql("delete from public.catalog_collections where id=:id").param("id",collection).update();
      jdbc.sql("delete from public.catalog_books where id=:a or id=:b")
          .param("a",older).param("b",newer).update();
    }
  }

  void insertPublicBook(UUID id,String key,String title,OffsetDateTime published) {
    jdbc.sql("""
        insert into public.catalog_books
          (id,book_key,source,title,language,rights,text_path,status,published_at)
        values (:id,:key,'admin',:title,'pt','public_domain',:path,'published',:published)
        """)
      .param("id",id).param("key",key).param("title",title)
      .param("path",id+".txt").param("published",published).update();
  }

  String publicListJson(String slug,String sort) throws Exception {
    var request=MockMvcRequestBuilders.get("/api/v1/catalog/books")
        .param("collection",slug).param("limit","20");
    if (sort!=null) request=request.param("sort",sort);
    return mvc.perform(request)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  // --- Task 8: matriz de permissões da seção 3 da spec ---

  static Stream<Arguments> permissionMatrix() {
    List<String> endpoints = List.of(
        "overview",
        "users.search", "users.detail", "users.sessions.revoke",
        "roles.grant", "roles.revoke",
        "features.list", "features.catalog", "features.users", "features.set",
        "audit",
        "forum.reports", "forum.topics", "forum.replies",
        "acervo.review");
    return Stream.of("superadmin", "moderator", "support", "none")
        .flatMap(role -> endpoints.stream().map(endpoint -> Arguments.of(role, endpoint)));
  }

  static boolean allowedFor(String role, String endpoint) {
    if ("none".equals(role)) return false;
    if ("overview".equals(endpoint) || endpoint.startsWith("forum.")) return true;
    if (endpoint.startsWith("users.")) return "superadmin".equals(role) || "support".equals(role);
    if ("acervo.review".equals(endpoint)) return "superadmin".equals(role) || "moderator".equals(role);
    return "superadmin".equals(role);
  }

  @ParameterizedTest(name = "{0} em {1}")
  @MethodSource("permissionMatrix")
  void permissionMatrix(String role, String endpoint) throws Exception {
    AuthResponse actor = "none".equals(role) ? user() : userWithRole(role);
    String bearer = "Bearer " + actor.accessToken();
    UUID target = id(user());
    int status = switch (endpoint) {
      case "overview" -> getStatus(bearer, "/api/v1/admin/overview");
      case "users.search" -> getStatus(bearer, "/api/v1/admin/users?q=" + target);
      case "users.detail" -> getStatus(bearer, "/api/v1/admin/users/" + target);
      case "users.sessions.revoke" -> postStatus(bearer, "/api/v1/admin/users/" + target + "/sessions/revoke");
      case "roles.grant" -> putStatus(bearer, "/api/v1/admin/users/" + target + "/roles/moderator");
      case "roles.revoke" -> deleteStatus(bearer, "/api/v1/admin/users/" + target + "/roles/moderator");
      case "features.list" -> getStatus(bearer, "/api/v1/admin/features?userId=" + target);
      case "features.catalog" -> getStatus(bearer, "/api/v1/admin/features/catalog");
      case "features.users" -> getStatus(bearer, "/api/v1/admin/features/library/users");
      case "features.set" -> putStatus(bearer, "/api/v1/admin/features",
          "{\"userId\":\"" + target + "\",\"feature\":\"library\",\"enabled\":true}");
      case "audit" -> getStatus(bearer, "/api/v1/admin/audit");
      case "forum.reports" -> getStatus(bearer, "/api/v1/admin/forum/reports");
      case "forum.topics" -> getStatus(bearer, "/api/v1/admin/forum/topics");
      case "forum.replies" -> getStatus(bearer, "/api/v1/admin/forum/replies");
      case "acervo.review" -> {
        UUID submission = pendingSubmission().id();
        yield patchStatus(bearer, "/api/v1/community/submissions/" + submission, "{\"status\":\"approved\"}");
      }
      default -> throw new IllegalArgumentException(endpoint);
    };
    assertEquals(allowedFor(role, endpoint) ? 200 : 403, status, role + " em " + endpoint);
  }

  int getStatus(String bearer, String url) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", bearer))
        .andReturn().getResponse().getStatus();
  }

  int postStatus(String bearer, String url) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", bearer))
        .andReturn().getResponse().getStatus();
  }

  int putStatus(String bearer, String url) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.put(url).header("Authorization", bearer))
        .andReturn().getResponse().getStatus();
  }

  int putStatus(String bearer, String url, String json) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.put(url).header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(json))
        .andReturn().getResponse().getStatus();
  }

  int deleteStatus(String bearer, String url) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.delete(url).header("Authorization", bearer))
        .andReturn().getResponse().getStatus();
  }

  int patchStatus(String bearer, String url, String json) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.patch(url).header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content(json))
        .andReturn().getResponse().getStatus();
  }
}
