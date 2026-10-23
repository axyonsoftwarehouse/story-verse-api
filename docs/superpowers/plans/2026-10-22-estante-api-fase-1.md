# Estante (biblioteca pessoal) — API, Fase 1 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor a estante de cada conta (`library_entries`) em `/api/v1/me/library`, liberada só para quem tem a capability `library`.

**Architecture:** Pacote novo `com.storyverse.api.library`, no mesmo molde de `reading/`: controller fino que lê o Bearer, serviço `@Transactional` que autentica, exige a feature e marca a sessão do banco (`DbSession.setUser`), repositório com `JdbcClient` e SQL explícito. Toda escrita monta a entrada final, normaliza e valida num só lugar (`LibraryRules`) antes de gravar. Concorrência otimista por `updated_at`, como em `reading-state`.

**Tech Stack:** Java 25, Spring Boot (Web MVC, JDBC `JdbcClient`), Flyway, PostgreSQL 16 com RLS, JUnit 5 + MockMvc.

**Spec:** `story-magic/docs/SPEC_BIBLIOTECA_PESSOAL.md` (repositório story-verse-web), seções 7, 8, 12, 13, 15.2 e 18.

## Global Constraints

- Migração: `src/main/resources/db/migration/V15__library.sql` (V14 é `feature_access`).
- Toda rota exige a capability `library`: `FeatureAccessService.require(userId, "library")` → 403 `FEATURE_DISABLED`.
- `user_id` vem sempre do token; nunca do corpo.
- JSON em camelCase; banco em snake_case. Erros no formato `{ "error": "...", "code": ... }` (`AuthException`), exceto o 409 (Task 3).
- `book_key`: `^(gb:[0-9]+|cm:[0-9a-fA-F-]{36}|local:-?[0-9]+)$`; `source` ∈ `gutenberg`, `community`, `local` e precisa bater com o prefixo.
- `status` ∈ `want_to_read`, `reading`, `finished`, `abandoned` (sem `paused`/`rereading` — D2).
- Nota: 0,5 a 5, passo 0,5 (D1). Resenha até 4.000 caracteres, privada (D3/D4). `dnf_reason` ∈ `slow`, `not_for_me`, `difficult`, `other`; `dnf_note` até 500.
- Limites de texto: `title` 1–300 (obrigatório), `author` 300, `cover_url` 500, `series` 200, `genre` 80 (D8), `progress_label` 200; `progress_percent` 0–100; `language` `pt`|`en`.
- Sem coluna de visibilidade (D3/D10). Sem diamantes (D5).
- Escritas: 60 por minuto por conta (`RateLimitGate`, chave `library-write:<uuid>`). Listagem: `limit` máximo 100.
- Não alterar `reading_state` nem `book_submissions`.
- Não registrar conteúdo de resenha/nota em log ou telemetria.

## Decisões deste plano (lacunas da spec)

1. **Sync vê exclusões.** A política de SELECT não filtra `deleted_at` (a spec filtrava); o repositório esconde apagadas por padrão e as devolve, com `deletedAt`, quando o cliente pede `updatedSince` (Task 5). Sem isso, a remoção feita num aparelho nunca chega ao outro.
2. **Nota exige que o livro tenha sido terminado**: `rating` só é aceito com `finishedOn` preenchido (não com `status = finished`). Assim "recomeçar" (`finished → reading`, permitido na máquina de estados 6.4) mantém nota e data.
3. **Saindo de `abandoned`, `dnfReason`/`dnfNote` são apagados** pela normalização (não dá 422).
4. **"Hoje" é a data de São Paulo** (`America/Sao_Paulo`) ao preencher `finishedOn` e no `year` padrão de `stats`.
5. **`finish` e `abandon` exigem a entrada** (404 `Livro fora da estante.`); o cliente cria com `PUT` ao abrir o livro. Não usam `baseUpdatedAt` (são ações explícitas). `keepForLater` da spec não entra: abandonado sempre fica na estante, e "retomar" é `PATCH status=reading`.
6. **409 devolve a entrada atual**: corpo `{ "error": "A entrada mudou em outro aparelho.", "code": "CONFLICT", "current": <entrada> }`.
7. **`PUT` sobre entrada existente exige `baseUpdatedAt`** igual ao atual; `null` com linha viva → 409, **exceto se o conteúdo for igual ao atual**: aí devolve a entrada sem gravar (repetição de rede continua idempotente, como pede a spec 8.1). Linha apagada conta como ausente (o `PUT` a revive).
8. **Teto de 5.000 entradas vivas por conta** → 422 `Estante cheia.` (proteção contra abuso; a spec não fixa número).
9. **Paginação:** `sort=recent` usa cursor por chave (`updated_at`, `book_key`); os outros `sort` usam cursor de deslocamento. Estantes têm no máximo 5.000 linhas, então o custo é aceitável.
10. **Datas no futuro:** `startedOn`/`finishedOn` depois de amanhã (São Paulo) → 422 `Data no futuro.`

## Review Focus

- **Dois aparelhos editando a mesma entrada:** `PATCH` com `baseUpdatedAt` antigo deve dar 409 com a entrada atual, nunca sobrescrever em silêncio → teste na Task 4.
- **Remoção feita em outro aparelho:** `GET ?updatedSince=` tem de trazer a entrada apagada com `deletedAt` → teste na Task 5.
- **Recomeçar um livro terminado:** `PATCH status=reading` num `finished` com nota mantém nota e `finishedOn`, sem 422 → teste na Task 4.
- **Conta sem `library` ou de outra pessoa:** 403 `FEATURE_DISABLED` em todas as rotas, inclusive `GET`; conta B não lê nem altera entrada de A → testes nas Tasks 3 e 7.
- **Chaves reais de importados:** `local:-1729630000000123` (id de `newLocalBookId`) e `cm:` com UUID maiúsculo são aceitos; `gb:abc`, `local:`, `gb:12 ` → 400 → teste na Task 2.

## Como rodar os testes

Os testes de integração rodam contra um Postgres descartável, como no CI (`.github/workflows/ci.yml`):

```bash
docker run -d --name sv-test-db -e POSTGRES_PASSWORD=local-audit-test-only -p 5432:5432 postgres:16-alpine
PGPASSWORD=local-audit-test-only psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -f src/test/resources/schema-postgres.sql
STORYVERSE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/postgres ./mvnw -q test -Dtest=LibraryIntegrationTest
```

Exige JDK 25. Sem JDK 25 ou Docker na máquina, cada task é verificada fazendo push do branch e lendo o job "Verify API" do CI. **Nunca** apontar `STORYVERSE_TEST_DB_URL` para o banco de produção.

Branch: `feat/estante-api` a partir da `main`. Um commit por task.

---

### Task 1: Migração V15 e classe de teste

**Files:**
- Create: `src/main/resources/db/migration/V15__library.sql`
- Create: `src/test/java/com/storyverse/api/library/LibraryIntegrationTest.java`

**Interfaces:**
- Produces: tabela `public.library_entries` com as colunas da spec 7.2 (sem `visibility`, com `genre text check (genre is null or char_length(genre) <= 80)`); políticas RLS; grants a `authenticated`.
- Produces: `LibraryIntegrationTest` com a mesma configuração de `SecurityIntegrationTest` (`@SpringBootTest`, `@EnabledIfEnvironmentVariable(named="STORYVERSE_TEST_DB_URL", matches=".+")`, mesmo `@DynamicPropertySource`) e os helpers usados por todas as tasks: `AuthResponse userSession()` (signup), `UUID id(AuthResponse)`, `UUID user()` (= `id(userSession())`), `void grant(UUID uid)` (insere `feature_access(uid,'library')`) e `int status(Executable call)` (status da `AuthException` lançada).

- [ ] **Step 1: Escrever os testes de esquema**

```java
@Test void schemaRejectsInvalidRows() {
  UUID uid=user();
  insertRaw(uid,"gb:1","gutenberg","reading",null);                       // ok
  assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:x","gutenberg","reading",null));
  assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:2","gutenberg","paused",null));
  assertThrows(DataAccessException.class,()->insertRaw(uid,"gb:3","gutenberg","finished",new BigDecimal("4.3")));
}
@Test void deletingTheAccountRemovesTheShelf() {
  UUID uid=user(); insertRaw(uid,"gb:1","gutenberg","reading",null);
  jdbc.sql("delete from auth.users where id=:id").param("id",uid).update();
  assertEquals(0,count("select count(*) from public.library_entries where user_id=:id",uid));
}
```

`insertRaw` e `count` são helpers privados do teste com SQL direto (`title` fixo `"T"`).

- [ ] **Step 2: Rodar e ver falhar** — `relation "public.library_entries" does not exist`.

- [ ] **Step 3: Escrever `V15__library.sql`**

Tabela e índices como na spec 7.2, com estas diferenças: sem `visibility`; com `genre`; o índice `library_entries_user_updated_idx` **sem** `where deleted_at is null` e com `book_key` no fim (`(user_id, updated_at desc, book_key)`), porque o sync lê apagadas. Políticas no padrão de V7/V9 (`drop policy if exists` antes de cada `create policy`): SELECT `using (user_id = auth.uid())` (decisão 1), INSERT `with check`, UPDATE `using`/`with check`, DELETE `using`, todas por `user_id = auth.uid()`. `grant select, insert, update, delete on public.library_entries to authenticated;`.

- [ ] **Step 4: Rodar e ver passar.**

- [ ] **Step 5: Commit** — `feat(library): tabela library_entries (V15) com RLS`

---

### Task 2: Chave do livro e regras da entrada

**Files:**
- Create: `src/main/java/com/storyverse/api/library/BookKey.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryEntry.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryRules.java`
- Test: `src/test/java/com/storyverse/api/library/LibraryRulesTest.java` (JUnit puro, sem Spring)

**Interfaces:**
- Produces: `record BookKey(String value, String source)` com `static BookKey parse(String raw)` — 400 `Livro inválido.` se não casar com o regex; `source` derivado do prefixo (`gb`→`gutenberg`, `cm`→`community`, `local`→`local`).
- Produces: `record LibraryEntry(String bookKey, String source, String status, BigDecimal rating, String review, LocalDate startedOn, LocalDate finishedOn, String dnfReason, String dnfNote, Integer progressPercent, String progressLabel, String title, String author, String coverUrl, String series, BigDecimal seriesIndex, String genre, String language, String updatedAt, int version, String deletedAt)` — é o corpo das respostas; `updatedAt` é `updated_at::text`, como em `reading-state`.
- Produces: `final class LibraryRules` com `static LocalDate today()` (São Paulo), `static LibraryEntry normalize(LibraryEntry e)` (decisão 3; `progressPercent` 100 quando `finished`) e `static void validate(LibraryEntry e)` — lança `AuthException` com as mensagens abaixo.

- [ ] **Step 1: Escrever os testes**

```java
@Test void parsesRealKeys() {
  assertEquals("local",BookKey.parse("local:-1729630000000123").source());
  assertEquals("community",BookKey.parse("cm:0F8FAD5B-D9CB-469F-A165-70867728950E").source());
  for(String bad:List.of("gb:abc","local:","gb:12 ","xx:1",""))
    assertEquals(400,assertThrows(AuthException.class,()->BookKey.parse(bad)).status());
}
@Test void ratingNeedsAFinishedDate() {
  assertEquals(422,status(()->LibraryRules.validate(entry("reading").withRating("4.5"))));
  assertDoesNotThrow(()->LibraryRules.validate(entry("reading").withRating("4.5").withFinishedOn(LibraryRules.today())));
  assertEquals(422,status(()->LibraryRules.validate(entry("finished").withFinishedOn(LibraryRules.today()).withRating("4.3"))));
}
@Test void abandonedNeedsAReasonAndResumingClearsIt() {
  assertEquals(422,status(()->LibraryRules.validate(entry("abandoned"))));
  assertNull(LibraryRules.normalize(entry("reading").withDnf("slow","x")).dnfReason());
}
@Test void datesAreCoherentAndNotInTheFuture() {
  LocalDate t=LibraryRules.today();
  assertEquals(422,status(()->LibraryRules.validate(entry("finished").withStartedOn(t).withFinishedOn(t.minusDays(1)))));
  assertEquals(422,status(()->LibraryRules.validate(entry("reading").withStartedOn(t.plusDays(2)))));
}
@Test void rejectsOversizedText() {
  assertEquals(400,status(()->LibraryRules.validate(entry("reading").withReview("x".repeat(4001)))));
  assertEquals(400,status(()->LibraryRules.validate(entry("reading").withGenre("x".repeat(81)))));
}
```

`entry(status)` e os `with…` são helpers do teste que copiam o record (`title "T"`, `bookKey "gb:1"`, `source "gutenberg"`).

- [ ] **Step 2: Rodar e ver falhar** (classes não existem).

- [ ] **Step 3: Implementar** com as mensagens: status inválido → 400 `Status inválido.`; título ausente/grande → 400 `Informe o título.`/`Título grande demais.`; textos → 400 `<Campo> grande demais.`; `progressPercent` fora de 0–100 → 400 `Progresso inválido.`; idioma → 400 `Idioma inválido.`; `dnfReason` fora da lista → 400 `Motivo inválido.`; nota fora da escala → 422 `Nota inválida.`; nota sem `finishedOn` → 422 `Só livros terminados recebem nota.`; `abandoned` sem motivo → 422 `Informe o motivo do abandono.`; início depois da conclusão → 422 `A data de início é depois da conclusão.`; data depois de amanhã → 422 `Data no futuro.`; `source` diferente do prefixo → 400 `Origem não confere com o livro.`

- [ ] **Step 4: Rodar e ver passar.**

- [ ] **Step 5: Commit** — `feat(library): chave do livro e regras da entrada`

---

### Task 3: Ler e gravar uma entrada (`GET`/`PUT` por livro)

**Files:**
- Create: `src/main/java/com/storyverse/api/library/LibraryRepository.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryService.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryWriteRequest.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryConflictException.java`
- Test: `LibraryIntegrationTest`

**Interfaces:**
- Consumes: `BookKey`, `LibraryEntry`, `LibraryRules` (Task 2); `FeatureAccessService.require`, `AuthService.authenticate`, `DbSession.setUser`, `RateLimitGate.check`.
- Produces: `record LibraryWriteRequest(String source, String status, BigDecimal rating, String review, String startedOn, String finishedOn, String dnfReason, String dnfNote, Integer progressPercent, String progressLabel, String title, String author, String coverUrl, String series, BigDecimal seriesIndex, String genre, String language, String baseUpdatedAt)` — datas `yyyy-MM-dd` (inválida → 400 `Data inválida.`).
- Produces: `class LibraryConflictException extends RuntimeException` com `LibraryEntry current()`.
- Produces em `LibraryService`: `LibraryEntry get(String accessToken, String bookKey)` (404 `Livro fora da estante.` se ausente ou apagada) e `LibraryEntry put(String accessToken, String bookKey, LibraryWriteRequest request)`.
- Produces em `LibraryRepository`: `Optional<LibraryEntry> find(UUID userId, String bookKey)` (inclui apagadas), `int countLive(UUID userId)`, `Optional<LibraryEntry> insert(UUID userId, LibraryEntry e)` (vazio se outro aparelho criou antes), `Optional<LibraryEntry> replaceIfMatches(UUID userId, LibraryEntry e, String baseUpdatedAt)` (atualiza com `updated_at = now()`, `version = version + 1`, `deleted_at = null`; vazio se `updated_at` mudou) e `Optional<LibraryEntry> revive(UUID userId, LibraryEntry e)` (só onde `deleted_at is not null`).
- Toda escrita chama `limits.check("library-write:" + userId, 60, Duration.ofMinutes(1))` antes de gravar.

- [ ] **Step 1: Escrever os testes**

```java
@Test void putCreatesThenNeedsTheCurrentBase() {
  AuthResponse s=userSession(); grant(id(s));
  LibraryEntry created=library.put(s.accessToken(),"gb:1",write("reading",null));
  assertEquals(1,created.version());
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
@Test void putRejectsSourceMismatchAndFullShelf() {
  AuthResponse s=userSession(); grant(id(s));
  assertEquals(400,status(()->library.put(s.accessToken(),"gb:1",write("reading",null).withSource("local"))));
  fillShelf(id(s),5000);   // insere 5000 linhas vivas via SQL direto
  assertEquals(422,status(()->library.put(s.accessToken(),"gb:999999",write("reading",null))));
}
```

`write(status, base)` monta um `LibraryWriteRequest` com `source "gutenberg"`, `title "Dom Casmurro"`.

- [ ] **Step 2: Rodar e ver falhar.**

- [ ] **Step 3: Implementar.** `put`: autentica → `require(userId,"library")` → `DbSession.setUser` → limite → `BookKey.parse` → monta a entrada final a partir do corpo → `normalize` → `validate` → se não há linha: checa `countLive < 5000` (senão 422 `Estante cheia.`) e `insert`; se há linha apagada: `revive`; se há linha viva: `replaceIfMatches` com `baseUpdatedAt` (`null` ou diferente → `LibraryConflictException(atual)`). `finishedOn` nulo com `status=finished` vira `LibraryRules.today()`.

- [ ] **Step 4: Rodar e ver passar.**

- [ ] **Step 5: Commit** — `feat(library): ler e gravar uma entrada com concorrência otimista`

---

### Task 4: `PATCH`, `DELETE`, `finish` e `abandon`

**Files:**
- Create: `src/main/java/com/storyverse/api/library/FinishRequest.java`
- Create: `src/main/java/com/storyverse/api/library/AbandonRequest.java`
- Modify: `LibraryService.java`, `LibraryRepository.java`
- Test: `LibraryIntegrationTest`

**Interfaces:**
- Produces: `record FinishRequest(String finishedOn, BigDecimal rating, String review)` e `record AbandonRequest(String dnfReason, String dnfNote)`.
- Produces em `LibraryService`: `LibraryEntry patch(String accessToken, String bookKey, JsonNode body)` — só os campos presentes mudam, `null` explícito limpa; `source` e `bookKey` não podem ser alterados (400 `Campo não pode ser alterado.`); `baseUpdatedAt` obrigatório (400 `Informe baseUpdatedAt.`); divergente → `LibraryConflictException`. `void delete(String accessToken, String bookKey)` — soft delete (`deleted_at = now()`, `updated_at = now()`, `version + 1`), idempotente se já apagada, 404 se nunca existiu. `LibraryEntry finish(String accessToken, String bookKey, FinishRequest r)` e `LibraryEntry abandon(String accessToken, String bookKey, AbandonRequest r)` — decisão 5.
- Produces em `LibraryRepository`: `Optional<LibraryEntry> softDelete(UUID userId, String bookKey)`; `finish`/`abandon` reutilizam `replaceIfMatches` com o `updatedAt` lido na mesma transação (`select … for update`).

- [ ] **Step 1: Escrever os testes**

```java
@Test void staleDeviceGetsConflictWithCurrentEntry() {
  AuthResponse s=userSession(); grant(id(s));
  LibraryEntry v1=library.put(s.accessToken(),"gb:1",write("reading",null));
  LibraryEntry v2=library.patch(s.accessToken(),"gb:1",json("{\"progressPercent\":40,\"baseUpdatedAt\":\""+v1.updatedAt()+"\"}"));
  LibraryConflictException c=assertThrows(LibraryConflictException.class,
      ()->library.patch(s.accessToken(),"gb:1",json("{\"progressPercent\":10,\"baseUpdatedAt\":\""+v1.updatedAt()+"\"}")));
  assertEquals(40,c.current().progressPercent()); assertEquals(v2.version(),c.current().version());
}
@Test void restartingAFinishedBookKeepsRatingAndDate() {
  AuthResponse s=userSession(); grant(id(s));
  library.put(s.accessToken(),"gb:1",write("reading",null));
  LibraryEntry done=library.finish(s.accessToken(),"gb:1",new FinishRequest(null,new BigDecimal("4.5"),"Ótimo."));
  assertEquals(LibraryRules.today(),done.finishedOn()); assertEquals(100,done.progressPercent());
  LibraryEntry again=library.patch(s.accessToken(),"gb:1",json("{\"status\":\"reading\",\"baseUpdatedAt\":\""+done.updatedAt()+"\"}"));
  assertEquals(new BigDecimal("4.5"),again.rating()); assertEquals(done.finishedOn(),again.finishedOn());
}
@Test void finishIsIdempotentAndAdjustsStartDate() {
  AuthResponse s=userSession(); grant(id(s));
  library.put(s.accessToken(),"gb:1",write("reading",null).withStartedOn("2026-10-10"));
  LibraryEntry f=library.finish(s.accessToken(),"gb:1",new FinishRequest("2026-10-01",null,null));
  assertEquals(LocalDate.parse("2026-10-01"),f.startedOn());
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
  assertNull(library.put(s.accessToken(),"gb:1",write("reading",null)).deletedAt());
}
```

- [ ] **Step 2: Rodar e ver falhar.**

- [ ] **Step 3: Implementar** os quatro métodos. Todos passam por `normalize` + `validate` e pelo limite de escrita. Em `finish`, `rating`/`review` nulos mantêm os valores atuais; `finishedOn` anterior a `startedOn` move `startedOn` para `finishedOn`.

- [ ] **Step 4: Rodar e ver passar.**

- [ ] **Step 5: Commit** — `feat(library): patch, remoção, concluir e abandonar`

---

### Task 5: Listagem com filtros, ordenação, cursor e sync

**Files:**
- Create: `src/main/java/com/storyverse/api/library/LibraryQuery.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryPage.java`
- Create: `src/main/java/com/storyverse/api/library/LibraryCursor.java`
- Modify: `LibraryService.java`, `LibraryRepository.java`
- Test: `LibraryIntegrationTest`

**Interfaces:**
- Produces: `record LibraryQuery(List<String> status, String author, String series, String language, String genre, Integer year, BigDecimal minRating, BigDecimal maxRating, String sort, String cursor, Integer limit, String updatedSince)`.
- Produces: `record LibraryPage(List<LibraryEntry> items, String nextCursor)`.
- Produces: `record LibraryCursor` no molde de `forum/ForumCursor`: `recent` codifica `updated_at::text` + `book_key` (base64url); os demais codificam `o:<deslocamento>`. Cursor malformado → 400 `Cursor inválido.`
- Produces em `LibraryService`: `LibraryPage list(String accessToken, LibraryQuery q)`.
- Regras: `status` aceita CSV; `author`/`series` comparam com `lower(...)` exato; `genre` exato, e `"Da comunidade"` nunca casa; `year` filtra `extract(year from finished_on)`; `sort` ∈ `recent` (padrão, `updated_at desc, book_key`), `title`, `author`, `rating` (desc, nulos por último), `finished` (`finished_on desc`, nulos por último); `limit` padrão 50, máximo 100 (acima → 100); valores fora da lista → 400. Sem `updatedSince`, apagadas ficam de fora. Com `updatedSince` (texto de `updatedAt`): só `updated_at > updatedSince`, **inclui apagadas** com `deletedAt`, força `sort=recent` e ignora os demais filtros.

- [ ] **Step 1: Escrever os testes**

```java
@Test void listsWithFiltersSortAndCursor() {
  AuthResponse s=userSession(); grant(id(s));
  for(int i=1;i<=5;i++) library.put(s.accessToken(),"gb:"+i,write(i%2==0?"finished":"reading",null).withTitle("T"+i));
  LibraryPage p1=library.list(s.accessToken(),query().limit(2));
  LibraryPage p2=library.list(s.accessToken(),query().limit(2).cursor(p1.nextCursor()));
  assertEquals(2,p1.items().size()); assertNotEquals(p1.items().get(0).bookKey(),p2.items().get(0).bookKey());
  assertEquals(2,library.list(s.accessToken(),query().status("finished")).items().size());
  assertEquals("T1",library.list(s.accessToken(),query().sort("title")).items().get(0).title());
  assertEquals(400,status(()->library.list(s.accessToken(),query().sort("xyz"))));
}
@Test void syncSeesDeletionsFromAnotherDevice() {
  AuthResponse s=userSession(); grant(id(s));
  LibraryEntry e=library.put(s.accessToken(),"gb:1",write("reading",null));
  library.delete(s.accessToken(),"gb:1");
  assertTrue(library.list(s.accessToken(),query()).items().isEmpty());
  LibraryEntry tomb=library.list(s.accessToken(),query().updatedSince(e.updatedAt())).items().get(0);
  assertNotNull(tomb.deletedAt());
}
@Test void communityLabelIsNotAGenre() {
  AuthResponse s=userSession(); grant(id(s));
  library.put(s.accessToken(),"gb:1",write("reading",null).withGenre("Da comunidade"));
  assertTrue(library.list(s.accessToken(),query().genre("Da comunidade")).items().isEmpty());
}
```

`query()` é um builder de teste sobre `LibraryQuery`.

- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar** o SQL dinâmico no repositório com parâmetros nomeados (nunca concatenar valores do cliente; a coluna do `order by` sai de um `switch` sobre os valores permitidos).
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** — `feat(library): listagem com filtros, ordenação, cursor e sync`

---

### Task 6: Estatísticas do ano

**Files:**
- Create: `src/main/java/com/storyverse/api/library/LibraryStats.java`
- Modify: `LibraryService.java`, `LibraryRepository.java`
- Test: `LibraryIntegrationTest`

**Interfaces:**
- Produces: `record LibraryStats(int year, int finished, int abandoned, int reading, int wantToRead, int rated, BigDecimal averageRating, Map<String,Integer> finishedByGenre, Map<Integer,Integer> finishedByMonth)`.
- Produces em `LibraryService`: `LibraryStats stats(String accessToken, Integer year)` — `year` nulo = ano de `LibraryRules.today()`; fora de 2000…ano atual+1 → 400 `Ano inválido.` `finished`, `rated`, `averageRating` (1 casa, `HALF_UP`), `finishedByGenre` e `finishedByMonth` contam entradas vivas com `finished_on` no ano; `abandoned` conta `status=abandoned` com `updated_at` no ano; `reading`/`wantToRead` são o estado atual. `finishedByGenre` ignora `null` e `"Da comunidade"`.

- [ ] **Step 1: Escrever o teste**

```java
@Test void statsForTheYear() {
  AuthResponse s=userSession(); grant(id(s));
  library.put(s.accessToken(),"gb:1",write("reading",null).withGenre("Romance"));
  library.finish(s.accessToken(),"gb:1",new FinishRequest("2026-03-10",new BigDecimal("4"),null));
  library.put(s.accessToken(),"gb:2",write("reading",null).withGenre("Da comunidade"));
  library.finish(s.accessToken(),"gb:2",new FinishRequest("2026-03-20",new BigDecimal("5"),null));
  library.put(s.accessToken(),"gb:3",write("want_to_read",null));
  LibraryStats st=library.stats(s.accessToken(),2026);
  assertEquals(2,st.finished()); assertEquals(new BigDecimal("4.5"),st.averageRating());
  assertEquals(Map.of("Romance",1),st.finishedByGenre()); assertEquals(2,st.finishedByMonth().get(3));
  assertEquals(1,st.wantToRead());
}
```

- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar** com uma consulta agregada por bloco (contagens, gênero, mês).
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** — `feat(library): estatísticas do ano`

---

### Task 7: Rotas HTTP, exportação da conta e documentação

**Files:**
- Create: `src/main/java/com/storyverse/api/library/LibraryController.java`
- Modify: `src/main/java/com/storyverse/api/account/AccountService.java` (método `export`)
- Modify: `README.md` (seção de endpoints)
- Test: `src/test/java/com/storyverse/api/library/LibraryControllerTest.java` (MockMvc, como `ReadingStateControllerTest`) e `LibraryIntegrationTest`

**Interfaces:**
- Consumes: todos os métodos de `LibraryService` (Tasks 3–6).
- Produces: `@RestController @RequestMapping("/api/v1/me/library")` com `GET ""` (query params da `LibraryQuery`; `status` repetível ou CSV), `GET /stats?year=`, `GET /{bookKey}`, `PUT /{bookKey}`, `PATCH /{bookKey}`, `DELETE /{bookKey}` (204), `POST /{bookKey}/finish`, `POST /{bookKey}/abandon`. `/stats` declarado antes de `/{bookKey}`.
- Produces: `@ExceptionHandler(LibraryConflictException.class)` no controller → 409 com `{ "error": "A entrada mudou em outro aparelho.", "code": "CONFLICT", "current": <LibraryEntry> }`.
- Produces: `AccountService.export` ganha a chave `"library"` com todas as entradas da conta (inclusive apagadas), ordenadas por `updated_at`.

- [ ] **Step 1: Escrever os testes**

```java
// LibraryControllerTest
@Test void withoutTokenIsUnauthorized() throws Exception {
  mockMvc.perform(get("/api/v1/me/library")).andExpect(status().isUnauthorized());
}
// LibraryIntegrationTest (com MockMvc autowired)
@Test void httpConflictCarriesCurrentEntryAndGateIsEnforced() throws Exception {
  AuthResponse s=userSession(); grant(id(s));
  mvc.perform(put("/api/v1/me/library/gb:1").header("Authorization","Bearer "+s.accessToken())
      .contentType(JSON).content("{\"source\":\"gutenberg\",\"status\":\"reading\",\"title\":\"T\"}")).andExpect(status().isOk());
  mvc.perform(put("/api/v1/me/library/gb:1").header("Authorization","Bearer "+s.accessToken())
      .contentType(JSON).content("{\"source\":\"gutenberg\",\"status\":\"want_to_read\",\"title\":\"T\"}"))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"))
     .andExpect(jsonPath("$.current.bookKey").value("gb:1"));
  AuthResponse other=userSession();
  mvc.perform(get("/api/v1/me/library/stats").header("Authorization","Bearer "+other.accessToken()))
     .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
}
@Test void exportIncludesTheShelf() {
  AuthResponse s=userSession(); grant(id(s)); library.put(s.accessToken(),"gb:1",write("reading",null));
  assertEquals(1,((List<?>)account.export(s.accessToken()).get("library")).size());
}
@Test void writesAreRateLimited() {
  AuthResponse s=userSession(); grant(id(s));
  for(int i=0;i<60;i++) library.put(s.accessToken(),"gb:"+(1000+i),write("reading",null));
  assertEquals(429,status(()->library.put(s.accessToken(),"gb:2000",write("reading",null))));
}
```

- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar** o controller (cada método repassa `AuthTokens.bearer(authorization)`, como `ReadingStateController`), a chave em `export` e a seção do README listando as 8 rotas, os códigos 400/401/403/404/409/422/429 e a capability `library`.
- [ ] **Step 4: Rodar a suíte inteira** — `./mvnw --batch-mode verify` (com `STORYVERSE_TEST_DB_URL`) ou o job "Verify API" do CI: tudo verde.
- [ ] **Step 5: Commit** — `feat(library): rotas da estante, exportação e documentação`

---

## Fora deste plano

- Cliente web (`library.ts`, store, sync, telas), migração dos dados locais, conquistas e o status/nota no prompt do chat: plano próprio no story-verse-web.
- Liberação: depois do deploy, conceder `library` à equipe com `deploy/grant-feature.sh` (spec, seção 16).
