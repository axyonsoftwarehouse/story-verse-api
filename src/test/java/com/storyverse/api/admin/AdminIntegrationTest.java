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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.storyverse.api.auth.*;
import com.storyverse.api.common.DbSession;
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
}
