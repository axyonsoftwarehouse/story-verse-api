package com.storyverse.api;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.storyverse.api.auth.*;
import com.storyverse.api.community.CommunityService;
import com.storyverse.api.forum.*;
import com.storyverse.api.storage.*;
import com.storyverse.api.rpc.RpcService;
import com.storyverse.api.common.DbSession;

/** Run only against a disposable PostgreSQL database, never production. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class SecurityIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-security-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
  @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("STORYVERSE_TEST_DB_URL"));
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "local-audit-test-only");
    properties.add("spring.flyway.enabled", () -> "true");
    properties.add("app.storage.dir", () -> media.toString());
    properties.add("app.storage.quota-bytes", () -> "2048");
    properties.add("app.storage.quota-objects", () -> "3");
    properties.add("app.auth.require-email-confirmation", () -> "false");
  }
  @Autowired AuthService auth;
  @Autowired EmailTokenRepository emailTokens;
  @Autowired TokenService tokenService;
  @Autowired StorageService storage;
  @Autowired CommunityService community;
  @Autowired ForumService forum;
  @Autowired AuthzService authz;
  @Autowired RpcService rpc;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired com.storyverse.api.ai.AiBudget aiBudget;
  @Autowired com.storyverse.api.ai.AiMetrics aiMetrics;
  @Autowired com.storyverse.api.ai.AiCache aiCache;
  @Autowired com.storyverse.api.reading.ReadingStateService readingState;
  AuthResponse user() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Audit")); }
  UUID id(AuthResponse session) { return UUID.fromString(session.user().id().toString()); }

  @Test void refreshIsSingleUseUnderConcurrency() throws Exception {
    AuthResponse first = user();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> task = () -> { start.await(); try { auth.refresh(first.refreshToken()); return true; } catch (AuthException e) { assertEquals(401,e.status()); return false; } };
      Future<Boolean> a=executor.submit(task), b=executor.submit(task); start.countDown();
      assertNotEquals(a.get(10,TimeUnit.SECONDS), b.get(10,TimeUnit.SECONDS));
    }
  }
  @Test void logoutWithRotatedTokenRevokesTheStableSession() {
    AuthResponse old=user(), renewed=auth.refresh(old.refreshToken());
    auth.logout(old.refreshToken(),old.accessToken());
    assertThrows(AuthException.class, () -> auth.authenticate(renewed.accessToken()));
    assertThrows(AuthException.class, () -> auth.refresh(renewed.refreshToken()));
  }
  @Test void submittedTextIsImmutableAndApprovedTextCanBeReadByAnotherUser() {
    AuthResponse owner=user(), reader=user(), admin=user();
    String path=id(owner)+"/book.txt";
    storage.write(id(owner),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","reviewed".getBytes());
    var row=community.create(owner.accessToken(),Map.of("title","Book","text_path",path,"rights","own_work"));
    assertThrows(AuthException.class,()->storage.write(id(owner),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","changed".getBytes()));
    assertThrows(AuthException.class,()->storage.readFor(id(reader),StorageBucket.COMMUNITY_BOOKS,path));
    jdbc.sql("insert into public.admins(user_id) values(:id)").param("id",id(admin)).update();
    assertTrue(storage.readFor(id(admin),StorageBucket.COMMUNITY_BOOKS,path).isPresent());
    community.review(admin.accessToken(),UUID.fromString(row.get("id").toString()),Map.of("status","approved"));
    assertEquals("reviewed",new String(storage.readFor(id(reader),StorageBucket.COMMUNITY_BOOKS,path).orElseThrow()));
    assertThrows(AuthException.class,()->storage.delete(id(owner),StorageBucket.COMMUNITY_BOOKS,path));
    assertThrows(AuthException.class,()->community.delete(owner.accessToken(),UUID.fromString(row.get("id").toString())));
  }
  @Test void quotaIsAtomicAcrossConcurrentUploads() throws Exception {
    AuthResponse user=user();
    try (ExecutorService executor=Executors.newFixedThreadPool(2)) {
      Callable<Boolean> a=()->{try{storage.write(id(user),StorageBucket.COMMUNITY_BOOKS,id(user)+"/a.txt","text/plain",new byte[1500]);return true;}catch(AuthException e){return false;}};
      Callable<Boolean> b=()->{try{storage.write(id(user),StorageBucket.COMMUNITY_BOOKS,id(user)+"/b.txt","text/plain",new byte[1500]);return true;}catch(AuthException e){return false;}};
      Future<Boolean> one=executor.submit(a), two=executor.submit(b);
      assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
    }
  }
  @Test void clientTotalsNeverCreditReadingAndRapidHeartbeatsCannotMintTime() {
    AuthResponse user=user();UUID uid=id(user);
    rpc.call(uid,"report_reading",Map.of("p_days",Map.of("2099-01-01",43200)));
    assertEquals(0,jdbc.sql("select count(*) from public.reading_days where user_id=:id").param("id",uid).query(Integer.class).single());
    rpc.call(uid,"reading_heartbeat",Map.of("p_active",true));
    for(int i=0;i<4;i++)rpc.call(uid,"reading_heartbeat",Map.of("p_active",true));
    assertEquals(0,jdbc.sql("select coalesce(sum(seconds),0)::int from public.reading_days where user_id=:id").param("id",uid).query(Integer.class).single());
    jdbc.sql("update public.reading_clock set last_tick=clock_timestamp()-interval '15 seconds' where user_id=:id").param("id",uid).update();
    Number credit=(Number)rpc.call(uid,"reading_heartbeat",Map.of("p_active",true));
    assertTrue(credit.intValue()>=15 && credit.intValue()<=16);
  }
  @Test void runtimeRoleCannotWriteWalletsOrReadAnotherAccountsState() {
    AuthResponse a=user(), b=user();
    jdbc.sql("insert into public.reading_state(user_id,data) values(:id,'{}'::jsonb)").param("id",id(b)).update();
    new TransactionTemplate(transactions).executeWithoutResult(status->{
      jdbc.sql("set local role storyverse_api").update();
      DbSession.setUser(jdbc,id(a));
      assertEquals(0,jdbc.sql("select count(*) from public.reading_state").query(Integer.class).single());
      assertFalse(jdbc.sql("select has_table_privilege(current_user,'public.diamond_wallets','UPDATE')").query(Boolean.class).single());
      assertFalse(jdbc.sql("select rolsuper or rolbypassrls from pg_roles where rolname=current_user").query(Boolean.class).single());
    });
  }
  @Test void deleteWaitingForAnApprovalCannotRemoveTheApprovedSubmission() throws Exception {
    AuthResponse owner=user();String path=id(owner)+"/approval.txt";
    storage.write(id(owner),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","text".getBytes());
    var submission=community.create(owner.accessToken(),Map.of("title","Book","text_path",path,"rights","own_work"));
    UUID submissionId=UUID.fromString(submission.get("id").toString());
    try(var connection=dataSource.getConnection();ExecutorService executor=Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try(var statement=connection.prepareStatement("select id from public.book_submissions where id=? for update")) {
        statement.setObject(1,submissionId);statement.executeQuery().close();
      }
      Future<?> deletion=executor.submit(()->community.delete(owner.accessToken(),submissionId));
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while(!jdbc.sql("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%book_submissions%')").query(Boolean.class).single()) {
        if(System.nanoTime()>deadline)fail("deletion did not wait for the locked row");
        Thread.sleep(10);
      }
      try(var statement=connection.prepareStatement("update public.book_submissions set status='approved' where id=?")) {
        statement.setObject(1,submissionId);statement.executeUpdate();
      }
      connection.commit();
      ExecutionException rejected=assertThrows(ExecutionException.class,()->deletion.get(5,TimeUnit.SECONDS));
      assertInstanceOf(AuthException.class,rejected.getCause());
      assertEquals("approved",jdbc.sql("select status from public.book_submissions where id=:id").param("id",submissionId).query(String.class).single());
    }
  }
  @Test void runtimeRoleCanUseTheProtectedApplicationContracts() {
    AuthResponse user=user();UUID uid=id(user);String path=uid+"/runtime.txt";
    new TransactionTemplate(transactions).executeWithoutResult(status->{
      jdbc.sql("set local role storyverse_api").update();
      storage.write(uid,StorageBucket.COMMUNITY_BOOKS,path,"text/plain","runtime".getBytes());
      var row=community.create(user.accessToken(),Map.of("title","Runtime","text_path",path,"rights","own_work"));
      assertEquals(uid.toString(),row.get("user_id").toString());
      assertTrue(storage.readFor(uid,StorageBucket.COMMUNITY_BOOKS,path).isPresent());
      assertEquals(0,((Number)rpc.call(uid,"reading_heartbeat",Map.of("p_active",true))).intValue());
      var quota=aiBudget.reserve(uid);
      assertEquals(1,quota.used());
      assertEquals(quota.limit()-1,quota.remaining());
      assertEquals(1,jdbc.sql("select used from public.api_daily_usage where user_id=:uid").param("uid",uid).query(Integer.class).single());
      assertEquals(1,aiBudget.quota(uid).used());
      aiMetrics.record(uid,"chat","qa","qa-model","ok",12,10,5,false,null);
      assertEquals(1,jdbc.sql("select count(*) from public.ai_usage_events where user_id=:uid").param("uid",uid).query(Integer.class).single());
      String cacheKey=com.storyverse.api.ai.AiCache.key("qa",uid.toString());
      aiCache.put(cacheKey,"qa","{\"ok\":true}","qa-model",java.time.Duration.ofMinutes(5));
      assertTrue(aiCache.get(cacheKey).isPresent());
      auth.updateProfile(user.accessToken(),new ProfileUpdateRequest("Runtime","avatar-2",null,null));
      auth.logout(user.refreshToken(),user.accessToken());
    });
    assertThrows(AuthException.class,()->auth.authenticate(user.accessToken()));
  }
  @Test void closingAndUntrustedReportsCannotChangeFinalMinutesOrPayTwice() throws Exception {
    AuthResponse session=user();UUID uid=id(session);
    jdbc.sql("insert into public.reading_days(user_id,day,seconds) values(:uid,public.ranking_week_start(-1),600)").param("uid",uid).update();
    rpc.call(uid,"report_reading",Map.of("p_days",Map.of()));
    try(ExecutorService executor=Executors.newFixedThreadPool(2)) {
      Callable<Object> close=()->jdbc.sql("select public.close_reading_week(public.ranking_week_start(-1))").query((rs,n)->1).single();
      Future<Object> a=executor.submit(close),b=executor.submit(close);a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
    }
    String day=jdbc.sql("select public.ranking_week_start(-1)::text").query(String.class).single();
    rpc.call(uid,"report_reading",Map.of("p_days",Map.of(day,43200)));
    assertEquals(600,jdbc.sql("select seconds from public.reading_days where user_id=:uid and day=cast(:day as date)").param("uid",uid).param("day",day).query(Integer.class).single());
    assertTrue(jdbc.sql("select count(*) from public.diamond_ledger where user_id=:uid and reason='ranking'").param("uid",uid).query(Integer.class).single()<=1);
  }
  @Test void approvalRejectsSubmissionPointingToAnotherAuthorsFolder() {
    AuthResponse author=user(), victim=user(), admin=user();
    jdbc.sql("insert into public.admins(user_id) values(:id)").param("id",id(admin)).update();
    String path=id(victim)+"/stolen.txt";
    storage.write(id(victim),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","victim".getBytes());
    UUID submissionId=UUID.randomUUID();
    jdbc.sql("""
        insert into public.book_submissions(id,user_id,title,rights,text_path,status)
        values(:id,:uid,'Stolen','own_work',:path,'pending')
        """).param("id",submissionId).param("uid",id(author)).param("path",path).update();
    AuthException rejected=assertThrows(AuthException.class,
        () -> community.review(admin.accessToken(),submissionId,Map.of("status","approved")));
    assertEquals(409,rejected.status());
    assertEquals("pending",jdbc.sql("select status from public.book_submissions where id=:id").param("id",submissionId).query(String.class).single());
  }
  @Test void emailConfirmationAndPasswordResetTokensAreSingleUse() {
    AuthResponse session=user();UUID uid=id(session);String email=session.user().email();
    jdbc.sql("update auth.users set email_confirmed_at=null where id=:id").param("id",uid).update();
    String confirm=tokenService.newToken();
    emailTokens.create(uid,EmailTokenRepository.CONFIRM_EMAIL,tokenService.hash(confirm),java.time.Instant.now().plusSeconds(3600));
    AuthResponse confirmed=auth.confirmEmail(confirm);
    assertEquals(uid,UUID.fromString(confirmed.user().id()));
    assertEquals(1,jdbc.sql("select count(*) from auth.users where id=:id and email_confirmed_at is not null").param("id",uid).query(Integer.class).single());
    assertThrows(AuthException.class,()->auth.confirmEmail(confirm));
    String reset=tokenService.newToken();
    emailTokens.create(uid,EmailTokenRepository.RESET_PASSWORD,tokenService.hash(reset),java.time.Instant.now().plusSeconds(3600));
    auth.resetPassword(reset,"new-secret");
    assertThrows(AuthException.class,()->auth.login(new LoginRequest(email,"test-secret")));
    assertThrows(AuthException.class,()->auth.refresh(session.refreshToken()));
    assertNotNull(auth.login(new LoginRequest(email,"new-secret")).accessToken());
    assertThrows(AuthException.class,()->auth.resetPassword(reset,"another-secret"));
  }
  @Test void changingPasswordRequiresCurrentPasswordAndKeepsOnlyThisSession() {
    AuthResponse first=user();
    AuthResponse second=auth.login(new LoginRequest(first.user().email(),"test-secret"));
    auth.changePassword(first.accessToken(),"test-secret","other-secret");
    assertDoesNotThrow(()->auth.authenticate(first.accessToken()));
    assertThrows(AuthException.class,()->auth.refresh(second.refreshToken()));
    assertThrows(AuthException.class,()->auth.login(new LoginRequest(first.user().email(),"test-secret")));
    assertThrows(AuthException.class,()->auth.changePassword(first.accessToken(),"wrong","another-secret"));
  }
  @Test void profileUpdateKeepsUnchangedFieldsAndClearsRemovedOnes() {
    AuthResponse session=user();
    UserResponse updated=auth.updateProfile(session.accessToken(),
        new ProfileUpdateRequest("Novo nome","avatar-1","https://example.test/photo","https://example.test/cover"));
    assertEquals("Novo nome",updated.user_metadata().get("name"));
    assertEquals("avatar-1",updated.user_metadata().get("avatar"));
    updated=auth.updateProfile(session.accessToken(),new ProfileUpdateRequest("Novo nome",null,null,null));
    assertFalse(updated.user_metadata().containsKey("photo"));
    assertFalse(updated.user_metadata().containsKey("cover"));
  }
  @Test void rolesDriveCapabilitiesAndAdminAudit() {
    AuthResponse user=user(); UUID userId=id(user);
    assertTrue(authz.roles(userId).isEmpty());
    assertThrows(AuthException.class,()->authz.requireRole(userId,"superadmin"));
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'superadmin')").param("id",userId).update();
    assertTrue(authz.hasRole(userId,"superadmin"));
    assertTrue(auth.capabilities(user.accessToken()).roles().contains("superadmin"));
    authz.audit(userId,"test.action","user",userId.toString(),Map.of("ok",true));
    assertEquals(1,jdbc.sql("select count(*) from public.admin_audit_log where actor_id=:id").param("id",userId).query(Integer.class).single());
  }
  @Test void runtimeRoleCanReadOwnRolesAndWriteAudit() {
    AuthResponse user=user(); UUID uid=id(user);
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'superadmin')").param("id",uid).update();
    new TransactionTemplate(transactions).executeWithoutResult(status->{
      jdbc.sql("set local role storyverse_api").update();
      assertTrue(authz.hasRole(uid,"superadmin"));
      authz.audit(uid,"test.runtime","user",uid.toString(),Map.of("ok",true));
      assertEquals(1,jdbc.sql("select count(*) from public.admin_audit_log where actor_id=:id").param("id",uid).query(Integer.class).single());
    });
  }
  @Test void forumFlowCreatesTopicReplyReactionBookmarkReportAndModeration() {
    AuthResponse user=user(); UUID uid=id(user);
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'moderator')").param("id",uid).update();

    Map<String,Object> topic=forum.createTopic(user.accessToken(),Map.of(
        "space","duvidas","title","Como ler melhor?","body","Quero dicas de leitura.",
        "bookKey","gb:1342","spoilerLevel","none"));
    UUID topicId=UUID.fromString(topic.get("id").toString());
    assertEquals("published",topic.get("status"));

    Map<String,Object> reply=forum.createReply(user.accessToken(),topicId,Map.of("body","Leia devagar."));
    UUID replyId=UUID.fromString(reply.get("id").toString());
    assertEquals("published",reply.get("status"));

    ForumPage page=forum.topics(null,"duvidas",null,"recent",null,null,20);
    assertTrue(page.items().stream().anyMatch(row -> topicId.toString().equals(String.valueOf(row.get("id")))));
    assertEquals(1,forum.replies(null,topicId,null,20).items().size());
    assertEquals(1,((Number)forum.topic(null,topicId).get("reply_count")).intValue());

    Map<String,Object> reaction=forum.reactToTopic(user.accessToken(),topicId);
    assertEquals(true,reaction.get("liked"));
    assertEquals(1,((Number)reaction.get("reactionCount")).intValue());
    assertEquals(true,forum.toggleBookmark(user.accessToken(),topicId).get("saved"));

    Map<String,Object> report=forum.report(user.accessToken(),Map.of(
        "targetType","topic","targetId",topicId.toString(),"reasonCode","spam","details","teste"));
    UUID reportId=UUID.fromString(report.get("id").toString());
    assertEquals(1,forum.adminReports(user.accessToken(),"pending",20).size());

    Map<String,Object> resolved=forum.resolveReport(user.accessToken(),reportId,
        Map.of("status","actioned","resolutionNote","ok"));
    assertEquals("actioned",resolved.get("status"));

    Map<String,Object> accepted=forum.acceptReply(user.accessToken(),topicId,
        Map.of("replyId",replyId.toString()));
    assertEquals(replyId.toString(),String.valueOf(accepted.get("accepted_reply_id")));
  }
  @Test void deletingPendingSubmissionRemovesTheFileOnTheServer() {
    AuthResponse owner=user();String path=id(owner)+"/pending-delete.txt";
    String coverPath=id(owner)+"/livros/cover";
    String coverUrl="https://api.example.test/api/v1/storage/public/profile-media/"+coverPath;
    storage.write(id(owner),StorageBucket.COMMUNITY_BOOKS,path,"text/plain","bye".getBytes());
    storage.write(id(owner),StorageBucket.PROFILE_MEDIA,coverPath,"image/jpeg",new byte[]{1});
    var submission=community.create(owner.accessToken(),Map.of("title","Bye","text_path",path,"rights","own_work","cover_url",coverUrl));
    UUID submissionId=UUID.fromString(submission.get("id").toString());
    assertTrue(storage.readFor(id(owner),StorageBucket.COMMUNITY_BOOKS,path).isPresent());
    assertTrue(storage.readFor(id(owner),StorageBucket.PROFILE_MEDIA,coverPath).isPresent());
    community.delete(owner.accessToken(),submissionId);
    assertEquals(0,jdbc.sql("select count(*) from public.book_submissions where id=:id").param("id",submissionId).query(Integer.class).single());
    assertTrue(storage.readFor(id(owner),StorageBucket.COMMUNITY_BOOKS,path).isEmpty());
    assertTrue(storage.readFor(id(owner),StorageBucket.PROFILE_MEDIA,coverPath).isEmpty());
  }
}
