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
  @Autowired LibraryService library;

  /** Corpo de PUT mínimo (Dom Casmurro, Gutenberg) para variar um campo por vez. */
  static final class W {
    String source="gutenberg", status, title="Dom Casmurro", genre, startedOn, base;
    W(String status,String base) { this.status=status; this.base=base; }
    W withSource(String s) { source=s; return this; }
    W withTitle(String t) { title=t; return this; }
    W withGenre(String g) { genre=g; return this; }
    W withStartedOn(String d) { startedOn=d; return this; }
    LibraryWriteRequest build() {
      return new LibraryWriteRequest(source,status,null,null,startedOn,null,null,null,null,null,title,
          "Machado de Assis",null,null,null,genre,"pt",base);
    }
  }
  static LibraryWriteRequest write(String status,String base) { return new W(status,base).build(); }
  static W w(String status,String base) { return new W(status,base); }
  void fillShelf(UUID uid,int n) {
    jdbc.sql("""
        insert into public.library_entries(user_id,book_key,source,status,title)
        select :uid,'gb:'||g,'gutenberg','reading','T' from generate_series(1,:n) g
        """).param("uid",uid).param("n",n).update();
  }

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

  @Test void putCreatesThenNeedsTheCurrentBase() {
    AuthResponse s=userSession(); grant(id(s));
    LibraryEntry created=library.put(s.accessToken(),"gb:1",write("reading",null));
    assertEquals(1,created.version());
    assertEquals("Dom Casmurro",library.get(s.accessToken(),"gb:1").title());
    assertEquals(1,library.put(s.accessToken(),"gb:1",write("reading",null)).version());   // repetição idêntica
    LibraryConflictException c=assertThrows(LibraryConflictException.class,()->library.put(s.accessToken(),"gb:1",write("finished",null)));
    assertEquals(created.updatedAt(),c.current().updatedAt());
    assertEquals(2,library.put(s.accessToken(),"gb:1",write("reading",created.updatedAt())).version());
  }
  @Test void shelfIsPerAccountAndGated() {
    AuthResponse a=userSession(), b=userSession(); grant(id(a)); grant(id(b));
    library.put(a.accessToken(),"gb:1",write("reading",null));
    assertEquals(404,status(()->library.get(b.accessToken(),"gb:1")));
    AuthResponse noFeature=userSession();
    AuthException e=assertThrows(AuthException.class,()->library.get(noFeature.accessToken(),"gb:1"));
    assertEquals(403,e.status()); assertEquals("FEATURE_DISABLED",e.code());
  }
  @Test void putRejectsSourceMismatchBadDateAndFullShelf() {
    AuthResponse s=userSession(); grant(id(s));
    assertEquals(400,status(()->library.put(s.accessToken(),"gb:1",w("reading",null).withSource("local").build())));
    assertEquals(400,status(()->library.put(s.accessToken(),"gb:1",w("reading",null).withStartedOn("22/10/2026").build())));
    fillShelf(id(s),5000);
    assertEquals(422,status(()->library.put(s.accessToken(),"gb:999999",write("reading",null))));
  }

  static tools.jackson.databind.JsonNode json(String raw) { return tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw); }
  static String base(LibraryEntry e) { return "\"baseUpdatedAt\":\""+e.updatedAt()+"\""; }

  @Test void staleDeviceGetsConflictWithCurrentEntry() {
    AuthResponse s=userSession(); grant(id(s));
    LibraryEntry v1=library.put(s.accessToken(),"gb:1",write("reading",null));
    LibraryEntry v2=library.patch(s.accessToken(),"gb:1",json("{\"progressPercent\":40,"+base(v1)+"}"));
    LibraryConflictException c=assertThrows(LibraryConflictException.class,
        ()->library.patch(s.accessToken(),"gb:1",json("{\"progressPercent\":10,"+base(v1)+"}")));
    assertEquals(40,c.current().progressPercent()); assertEquals(v2.version(),c.current().version());
    assertEquals(400,status(()->library.patch(s.accessToken(),"gb:1",json("{\"progressPercent\":10}"))));
    assertEquals(400,status(()->library.patch(s.accessToken(),"gb:1",json("{\"source\":\"local\","+base(v2)+"}"))));
  }
  @Test void restartingAFinishedBookKeepsRatingAndDate() {
    AuthResponse s=userSession(); grant(id(s));
    library.put(s.accessToken(),"gb:1",write("reading",null));
    LibraryEntry done=library.finish(s.accessToken(),"gb:1",new FinishRequest(null,new BigDecimal("4.5"),"Ótimo."));
    assertEquals(LibraryRules.today(),done.finishedOn()); assertEquals(100,done.progressPercent());
    LibraryEntry again=library.patch(s.accessToken(),"gb:1",json("{\"status\":\"reading\","+base(done)+"}"));
    assertEquals(0,new BigDecimal("4.5").compareTo(again.rating())); assertEquals(done.finishedOn(),again.finishedOn());
    assertEquals("reading",again.status());
  }
  @Test void finishIsIdempotentAndAdjustsStartDate() {
    AuthResponse s=userSession(); grant(id(s));
    library.put(s.accessToken(),"gb:1",w("reading",null).withStartedOn("2026-10-10").build());
    LibraryEntry f=library.finish(s.accessToken(),"gb:1",new FinishRequest("2026-10-01",null,null));
    assertEquals(java.time.LocalDate.parse("2026-10-01"),f.startedOn());
    assertEquals("finished",library.finish(s.accessToken(),"gb:1",new FinishRequest("2026-10-01",null,null)).status());
  }
  @Test void abandonNeedsReasonAndFinishNeedsEntry() {
    AuthResponse s=userSession(); grant(id(s));
    assertEquals(404,status(()->library.finish(s.accessToken(),"gb:7",new FinishRequest(null,null,null))));
    library.put(s.accessToken(),"gb:1",write("reading",null));
    assertEquals(422,status(()->library.abandon(s.accessToken(),"gb:1",new AbandonRequest(null,null))));
    assertEquals("slow",library.abandon(s.accessToken(),"gb:1",new AbandonRequest("slow",null)).dnfReason());
  }
  @Test void deleteIsSoftAndPutRevives() {
    AuthResponse s=userSession(); grant(id(s));
    library.put(s.accessToken(),"gb:1",write("reading",null));
    library.delete(s.accessToken(),"gb:1"); library.delete(s.accessToken(),"gb:1");
    assertEquals(404,status(()->library.get(s.accessToken(),"gb:1")));
    assertEquals(404,status(()->library.delete(s.accessToken(),"gb:2")));
    assertNull(library.put(s.accessToken(),"gb:1",write("reading",null)).deletedAt());
  }

  /** Filtros da listagem, um por vez. */
  static final class Q {
    List<String> status; String genre, sort, cursor, updatedSince; Integer limit;
    Q status(String s) { status=List.of(s); return this; }
    Q genre(String g) { genre=g; return this; }
    Q sort(String s) { sort=s; return this; }
    Q cursor(String c) { cursor=c; return this; }
    Q limit(int l) { limit=l; return this; }
    Q updatedSince(String u) { updatedSince=u; return this; }
    LibraryQuery build() { return new LibraryQuery(status,null,null,null,genre,null,null,null,sort,cursor,limit,updatedSince); }
  }
  static Q query() { return new Q(); }

  @Test void listsWithFiltersSortAndCursor() {
    AuthResponse s=userSession(); grant(id(s));
    for(int i=1;i<=5;i++) library.put(s.accessToken(),"gb:"+i,w(i%2==0?"finished":"reading",null).withTitle("T"+i).build());
    LibraryPage p1=library.list(s.accessToken(),query().limit(2).build());
    LibraryPage p2=library.list(s.accessToken(),query().limit(2).cursor(p1.nextCursor()).build());
    LibraryPage p3=library.list(s.accessToken(),query().limit(2).cursor(p2.nextCursor()).build());
    assertEquals(2,p1.items().size()); assertEquals(2,p2.items().size()); assertEquals(1,p3.items().size());
    assertNull(p3.nextCursor());
    Set<String> seen=new HashSet<>();
    for(LibraryPage p:List.of(p1,p2,p3)) p.items().forEach(e->assertTrue(seen.add(e.bookKey())));
    assertEquals(2,library.list(s.accessToken(),query().status("finished").build()).items().size());
    LibraryPage byTitle=library.list(s.accessToken(),query().sort("title").limit(3).build());
    assertEquals("T1",byTitle.items().get(0).title());
    assertEquals("T4",library.list(s.accessToken(),query().sort("title").limit(3).cursor(byTitle.nextCursor()).build()).items().get(0).title());
    assertEquals(400,status(()->library.list(s.accessToken(),query().sort("xyz").build())));
    assertEquals(400,status(()->library.list(s.accessToken(),query().cursor("lixo").build())));
  }
  @Test void syncSeesDeletionsFromAnotherDevice() {
    AuthResponse s=userSession(); grant(id(s));
    LibraryEntry e=library.put(s.accessToken(),"gb:1",write("reading",null));
    library.delete(s.accessToken(),"gb:1");
    assertTrue(library.list(s.accessToken(),query().build()).items().isEmpty());
    LibraryEntry tomb=library.list(s.accessToken(),query().updatedSince(e.updatedAt()).build()).items().get(0);
    assertNotNull(tomb.deletedAt());
    assertEquals(400,status(()->library.list(s.accessToken(),query().updatedSince("ontem").build())));
  }
  @Test void communityLabelIsNotAGenre() {
    AuthResponse s=userSession(); grant(id(s));
    library.put(s.accessToken(),"gb:1",w("reading",null).withGenre("Da comunidade").build());
    library.put(s.accessToken(),"gb:2",w("reading",null).withGenre("Romance").build());
    assertTrue(library.list(s.accessToken(),query().genre("Da comunidade").build()).items().isEmpty());
    assertEquals(1,library.list(s.accessToken(),query().genre("Romance").build()).items().size());
  }

  @Test void statsForTheYear() {
    AuthResponse s=userSession(); grant(id(s));
    library.put(s.accessToken(),"gb:1",w("reading",null).withGenre("Romance").build());
    library.finish(s.accessToken(),"gb:1",new FinishRequest("2026-03-10",new BigDecimal("4"),null));
    library.put(s.accessToken(),"gb:2",w("reading",null).withGenre("Da comunidade").build());
    library.finish(s.accessToken(),"gb:2",new FinishRequest("2026-03-20",new BigDecimal("5"),null));
    library.put(s.accessToken(),"gb:3",write("want_to_read",null));
    library.put(s.accessToken(),"gb:4",write("reading",null));
    LibraryStats st=library.stats(s.accessToken(),2026);
    assertEquals(2026,st.year());
    assertEquals(2,st.finished()); assertEquals(2,st.rated()); assertEquals(new BigDecimal("4.5"),st.averageRating());
    assertEquals(Map.of("Romance",1),st.finishedByGenre()); assertEquals(2,st.finishedByMonth().get(3));
    assertEquals(1,st.wantToRead()); assertEquals(1,st.reading());
    assertEquals(0,library.stats(s.accessToken(),2025).finished());
    assertNull(library.stats(s.accessToken(),2025).averageRating());
    assertEquals(400,status(()->library.stats(s.accessToken(),1999)));
  }
}
