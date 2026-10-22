package com.storyverse.api.library;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import com.storyverse.api.auth.AuthException;

class LibraryRulesTest {

  /** Entrada mínima válida para variar um campo por vez. */
  static final class E {
    String bookKey="gb:1", source="gutenberg", status, review, dnfReason, dnfNote, genre, title="T";
    BigDecimal rating; LocalDate startedOn, finishedOn;
    E(String status) { this.status=status; }
    E rating(String r) { rating=new BigDecimal(r); return this; }
    E startedOn(LocalDate d) { startedOn=d; return this; }
    E finishedOn(LocalDate d) { finishedOn=d; return this; }
    E dnf(String reason,String note) { dnfReason=reason; dnfNote=note; return this; }
    E review(String r) { review=r; return this; }
    E genre(String g) { genre=g; return this; }
    E source(String s) { source=s; return this; }
    LibraryEntry build() {
      return new LibraryEntry(bookKey,source,status,rating,review,startedOn,finishedOn,dnfReason,dnfNote,
          null,null,title,null,null,null,null,genre,null,null,1,null);
    }
  }
  static E entry(String status) { return new E(status); }
  static int status(Executable call) { return assertThrows(AuthException.class,call).status(); }

  @Test void parsesRealKeys() {
    assertEquals("local",BookKey.parse("local:-1729630000000123").source());
    assertEquals("community",BookKey.parse("cm:0F8FAD5B-D9CB-469F-A165-70867728950E").source());
    assertEquals("gutenberg",BookKey.parse("gb:1342").source());
    for(String bad:List.of("gb:abc","local:","gb:12 ","xx:1",""))
      assertEquals(400,status(()->BookKey.parse(bad)),bad);
    assertEquals(400,status(()->BookKey.parse(null)));
  }
  @Test void ratingNeedsAFinishedDate() {
    assertEquals(422,status(()->LibraryRules.validate(entry("reading").rating("4.5").build())));
    assertDoesNotThrow(()->LibraryRules.validate(entry("reading").rating("4.5").finishedOn(LibraryRules.today()).build()));
    assertEquals(422,status(()->LibraryRules.validate(entry("finished").finishedOn(LibraryRules.today()).rating("4.3").build())));
  }
  @Test void abandonedNeedsAReasonAndResumingClearsIt() {
    assertEquals(422,status(()->LibraryRules.validate(entry("abandoned").build())));
    assertEquals(400,status(()->LibraryRules.validate(entry("abandoned").dnf("boring",null).build())));
    assertNull(LibraryRules.normalize(entry("reading").dnf("slow","x").build()).dnfReason());
  }
  @Test void finishingFillsTodayAndFullProgress() {
    LibraryEntry done=LibraryRules.normalize(entry("finished").build());
    assertEquals(LibraryRules.today(),done.finishedOn());
    assertEquals(100,done.progressPercent());
  }
  @Test void datesAreCoherentAndNotInTheFuture() {
    LocalDate t=LibraryRules.today();
    assertEquals(422,status(()->LibraryRules.validate(entry("finished").startedOn(t).finishedOn(t.minusDays(1)).build())));
    assertEquals(422,status(()->LibraryRules.validate(entry("reading").startedOn(t.plusDays(2)).build())));
    assertDoesNotThrow(()->LibraryRules.validate(entry("reading").startedOn(t.plusDays(1)).build()));
  }
  @Test void rejectsOversizedTextUnknownStatusAndWrongSource() {
    assertEquals(400,status(()->LibraryRules.validate(entry("reading").review("x".repeat(4001)).build())));
    assertEquals(400,status(()->LibraryRules.validate(entry("reading").genre("x".repeat(81)).build())));
    assertEquals(400,status(()->LibraryRules.validate(entry("paused").build())));
    assertEquals(400,status(()->LibraryRules.validate(entry("reading").source("local").build())));
  }
}
