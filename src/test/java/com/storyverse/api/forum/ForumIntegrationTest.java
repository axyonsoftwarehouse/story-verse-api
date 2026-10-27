package com.storyverse.api.forum;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.storyverse.api.auth.*;

/** Fórum contra PostgreSQL de verdade (paginação, moderação). Rode só num banco descartável. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")
class ForumIntegrationTest {
  static final Path media;
  static { try { media = Files.createTempDirectory("storyverse-forum-it-"); } catch (Exception e) { throw new RuntimeException(e); } }
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
  @Autowired AuthService auth;
  @Autowired ForumService forum;
  @Autowired JdbcClient jdbc;

  AuthResponse user() { return (AuthResponse) auth.signup(new SignupRequest(UUID.randomUUID()+"@example.invalid", "test-secret", "Leitor")); }
  UUID id(AuthResponse session) { return UUID.fromString(session.user().id().toString()); }
  AuthResponse staff() {
    AuthResponse session=user();
    jdbc.sql("insert into public.user_roles(user_id,role) values(:id,'moderator')").param("id",id(session)).update();
    return session;
  }
  int status(Executable action) { return assertThrows(AuthException.class, action).status(); }
  String space() {
    String slug="it-"+UUID.randomUUID().toString().substring(0,8);
    jdbc.sql("insert into public.forum_spaces(slug,name) values(:slug,'Teste')").param("slug",slug).update();
    return slug;
  }
  UUID topic(String slug, UUID author, String activity, boolean pinned, String status) {
    return jdbc.sql("""
        insert into public.forum_topics(space_id,author_id,title,body,status,is_pinned,last_activity_at,created_at)
        select s.id,:author,'Tópico de teste','Texto',:status,:pinned,cast(:at as timestamptz),cast(:at as timestamptz)
          from public.forum_spaces s where s.slug=:slug
        returning id
        """).param("slug",slug).param("author",author).param("status",status).param("pinned",pinned).param("at",activity)
        .query(UUID.class).single();
  }
  UUID reply(UUID topic, UUID author, String createdAt) {
    return jdbc.sql("""
        insert into public.forum_replies(topic_id,author_id,body,status,created_at)
        values(:topic,:author,'Resposta','published',cast(:at as timestamptz)) returning id
        """).param("topic",topic).param("author",author).param("at",createdAt).query(UUID.class).single();
  }
  static List<Object> ids(ForumPage page) { return page.items().stream().map(row -> row.get("id")).toList(); }

  /** Antes: a 2ª página dava 500 (Instant no JDBC) e o cursor em ms repetia a última resposta. */
  @Test void repliesPageThroughWithoutRepeatingInsideTheSameMillisecond() {
    UUID author=id(user());
    UUID topic=topic(space(),author,"2026-01-01T00:00:00Z",false,"published");
    UUID first=reply(topic,author,"2026-01-01T00:00:01.000100Z");
    UUID second=reply(topic,author,"2026-01-01T00:00:01.000200Z");
    UUID third=reply(topic,author,"2026-01-01T00:00:01.000300Z");
    List<Object> seen=new ArrayList<>();
    String cursor=null;
    for (int guard=0; guard<5; guard++) {
      ForumPage page=forum.replies(null,topic,cursor,1);
      seen.addAll(ids(page));
      cursor=page.nextCursor();
      if (cursor==null || page.items().isEmpty()) break;
    }
    assertEquals(List.of(first,second,third),seen);
  }

  @Test void pinnedTopicsComeFirstAndPaginationKeepsTheOrder() {
    String slug=space();
    UUID author=id(user());
    UUID oldPinned=topic(slug,author,"2026-01-01T00:00:00.000001Z",true,"published");
    UUID newest=topic(slug,author,"2026-01-03T00:00:00.000003Z",false,"published");
    UUID middle=topic(slug,author,"2026-01-02T00:00:00.000002Z",false,"published");
    topic(slug,author,"2026-01-04T00:00:00Z",false,"pending");
    List<Object> seen=new ArrayList<>();
    String cursor=null;
    for (int guard=0; guard<6; guard++) {
      ForumPage page=forum.topics(null,slug,null,"recent",null,cursor,1);
      seen.addAll(ids(page));
      cursor=page.nextCursor();
      if (cursor==null || page.items().isEmpty()) break;
    }
    assertEquals(List.of(oldPinned,newest,middle),seen);
  }

  @Test void searchTreatsPercentAsText() {
    String slug=space();
    UUID author=id(user());
    UUID literal=topic(slug,author,"2026-01-01T00:00:00Z",false,"published");
    jdbc.sql("update public.forum_topics set title='Nota 100% justa' where id=:id").param("id",literal).update();
    UUID other=topic(slug,author,"2026-01-02T00:00:00Z",false,"published");
    jdbc.sql("update public.forum_topics set title='Nota 1000 justa' where id=:id").param("id",other).update();
    assertEquals(List.of(literal),ids(forum.topics(null,slug,null,"recent","0%",null,20)));
  }

  @Test void pendingTopicIsOnlyVisibleToAuthorAndStaff() {
    AuthResponse author=user(), stranger=user(), moderator=staff();
    UUID topic=topic(space(),id(author),"2026-01-01T00:00:00Z",false,"pending");
    assertEquals(topic,forum.topic(author.accessToken(),topic).get("id"));
    assertEquals(topic,forum.topic(moderator.accessToken(),topic).get("id"));
    assertEquals(404,status(()->forum.topic(stranger.accessToken(),topic)));
    assertEquals(404,status(()->forum.topic(null,topic)));
    assertEquals(404,status(()->forum.replies(null,topic,null,10)));
    assertEquals(404,status(()->forum.toggleBookmark(stranger.accessToken(),topic)));
  }

  @Test void untrustedAuthorEditingApprovedTopicGoesBackToQueue() {
    AuthResponse author=user(), moderator=staff();
    UUID topic=topic(space(),id(author),"2026-01-01T00:00:00Z",false,"published");
    jdbc.sql("update public.forum_topics set created_at=now() where id=:id").param("id",topic).update();
    Map<String,Object> edited=forum.updateTopic(author.accessToken(),topic,Map.of("body","Trocado depois da aprovação"));
    assertEquals("pending",edited.get("status"));

    forum.moderateTopic(moderator.accessToken(),topic,Map.of("status","published"));
    Map<String,Object> byStaff=forum.updateTopic(moderator.accessToken(),topic,Map.of("title","Título revisado"));
    assertEquals("published",byStaff.get("status"));

    forum.moderateTopic(moderator.accessToken(),topic,Map.of("status","hidden"));
    assertEquals(403,status(()->forum.updateTopic(author.accessToken(),topic,Map.of("body","x"))));
  }

  @Test void replySpoilerCanBeEdited() {
    AuthResponse moderator=staff();
    UUID topic=topic(space(),id(moderator),"2026-01-01T00:00:00Z",false,"published");
    UUID reply=reply(topic,id(moderator),"2026-01-01T00:00:01Z");
    Map<String,Object> edited=forum.updateReply(moderator.accessToken(),reply,Map.of("spoilerLevel","book"));
    assertEquals("book",edited.get("spoiler_level"));
    assertEquals("Resposta",edited.get("body"));
  }

  @Test void hiddenAcceptedReplyStopsMarkingTopicAsSolved() {
    AuthResponse moderator=staff();
    UUID topic=topic(space(),id(moderator),"2026-01-01T00:00:00Z",false,"published");
    UUID reply=reply(topic,id(moderator),"2026-01-01T00:00:01Z");
    assertEquals(reply,forum.acceptReply(moderator.accessToken(),topic,Map.of("replyId",reply.toString())).get("accepted_reply_id"));
    forum.moderateReply(moderator.accessToken(),reply,Map.of("status","hidden"));
    assertNull(forum.topic(moderator.accessToken(),topic).get("accepted_reply_id"));
    forum.moderateReply(moderator.accessToken(),reply,Map.of("status","published"));
    assertEquals(reply,forum.topic(moderator.accessToken(),topic).get("accepted_reply_id"));
  }

  @Test void reportsAreRateLimited() {
    AuthResponse reporter=user();
    UUID author=id(user());
    String slug=space();
    int last=0;
    for (int i=0; i<11; i++) {
      UUID topic=topic(slug,author,"2026-01-01T00:00:00Z",false,"published");
      try {
        forum.report(reporter.accessToken(),Map.of("targetType","topic","targetId",topic.toString(),"reasonCode","spam"));
      } catch (AuthException error) {
        last=error.status();
      }
    }
    assertEquals(429,last);
  }
}
