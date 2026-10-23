# Área do admin — API, Fase 1 — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar à API o que o painel `/admin` precisa: uma só fonte de papéis (`user_roles`), auditoria na mesma transação da ação, e endpoints de contas, features, visão geral e auditoria.

**Architecture:** Migration V16 troca as leituras de `public.admins` por `user_roles` e cria `can_moderate()`. Pacote novo `com.storyverse.api.admin` no molde de `forum/` e `library/`: controller fino que lê o Bearer, serviço `@Transactional` que autentica, confere o papel com `AuthzService`, marca a sessão do banco (`DbSession.setUser`) e audita na mesma transação, e repositório com `JdbcClient` e SQL explícito. A RLS continua valendo (papel `storyverse_api`).

**Tech Stack:** Java 25, Spring Boot 4.1 (Web MVC, `JdbcClient`), Flyway, PostgreSQL 16 com RLS, JUnit 5 + MockMvc.

**Spec:** `story-magic/docs/superpowers/specs/2026-10-23-area-admin-design.md` (repositório web). Leia as seções 3 e 4 antes de começar.

## Global Constraints

- Migration: `src/main/resources/db/migration/V16__admin_roles.sql` (V15 é a estante).
- Papéis: `superadmin`, `moderator`, `support` (constantes de `AuthzService`). A tabela de permissões é a seção 3 da spec. Todo endpoint confere o papel no servidor.
- Toda ação que muda estado chama `authz.audit(...)` **dentro da mesma transação** (método de serviço `@Transactional`). Se a auditoria falhar, a ação é desfeita.
- Pelo painel, só `moderator` e `support` podem ser dados ou tirados. `superadmin` → 400. Alterar os próprios papéis → 403. Receber papel exige `email_confirmed_at` preenchido → 409.
- E-mail completo só no detalhe da conta. Em listas, sempre `EmailMask.mask(email)`.
- Nome exibido: `coalesce(u.raw_user_meta_data->>'name', 'Leitor')`, como em `ForumRepository`.
- Erros: `AuthException(status, mensagem)`, com mensagens em português. 401 sem token, 403 sem papel, 400 entrada inválida, 404 conta inexistente.
- JSON em camelCase. Datas como `Instant` ISO-8601.
- Nada de `bypassrls`. Não alterar `src/test/resources/schema-postgres.sql` (a V16 recria o que precisar).
- Não ler nem escrever `public.admins` em código novo. A tabela fica congelada (não apagar nesta fase).
- Nunca registrar e-mail completo em log.

## Decisões deste plano (lacunas da spec)

1. **Busca de contas:** `q` sem espaços nas pontas. Se for UUID → busca por id. Se tiver `@` → e-mail exato (`lower(email) = lower(:q)`). Se tiver 3 ou mais caracteres → parte do nome (`ilike`, com `%`, `_` e `\` escapados). Senão → 400 `Busque por UUID, e-mail completo ou ao menos 3 letras do nome.` Até 20 resultados, ordenados por `created_at desc`. Limite de 30 buscas por minuto por ator (`RateLimitGate`, chave `admin-user-search:<uuid>`).
2. **Máscara de e-mail:** os 2 primeiros caracteres da parte local (1 se ela tiver até 2), depois `***@` e o domínio inteiro. `wernersaboia@gmail.com` → `we***@gmail.com`. `ab@x.com` → `a***@x.com`. Sem `@` → `***`.
3. **Sessão ativa:** `revoked_at is null and refresh_expires_at > now()`.
4. **Encerrar sessões da própria conta pelo painel → 403** `Use "Sair de todos os aparelhos" na sua conta.`
5. **Papel idempotente:** dar um papel que a conta já tem, ou tirar um que ela não tem, responde 200 com os papéis atuais e **não** audita.
6. **Arquivos do envio apagados depois do commit,** por `StorageService.removeAfterCommit`, sem checagem de permissão por pessoa. A permissão já foi conferida pelo `CommunityService`, e o caminho vem da linha do banco. O texto só é removido se o caminho começar com `<autor>/`. Caso contrário, registra aviso e não apaga. O mesmo vale para a capa (regra que já existe). Falha ao apagar arquivo vira `log.warn`, não erro.
7. **Paginação:**
   - auditoria: `before=<id>`, `id < :before`, 50 por página, `nextBefore` nulo no fim;
   - quem tem uma feature: cursor `epochMillis:uuid` sobre (`updated_at desc`, `user_id desc`), 50 por página. Novo `admin/AdminCursor`, no mesmo formato de `ForumCursor`. O `ForumCursor` não muda.
8. **Filtro de período da auditoria:** `from` e `to` como datas `yyyy-MM-dd` no fuso `America/Sao_Paulo`, inclusivas. `action` filtra por prefixo (`forum.` traz todas as do fórum).
9. **Backup atrasado:** `stale = true` se não houver backup com `status = 'success'`, ou se o último tiver `finished_at` mais de 26 horas atrás.
10. **Denúncias abertas** = `forum_reports.status = 'pending'`.

## Review Focus

- **Conta sem nenhum papel ou com papel errado chamando cada endpoint `/api/v1/admin/**`:** deve dar 403, nunca 200 vazio. Suporte em papéis e features, e moderador em contas, são os casos mais prováveis de escapar → teste de matriz na Task 8.
- **Superadmin tentando tirar o próprio `superadmin`, ou dar `superadmin` a alguém, pelo painel:** 403 e 400, sem mudar `user_roles` → testes na Task 4.
- **Falha na auditoria depois da ação:** a feature não pode ficar alterada → teste na Task 2.
- **Busca curta ou genérica** (`a`, `%`, `_`): 400 ou nenhum resultado, nunca a base inteira → teste na Task 3.
- **Moderador apagando um envio aprovado com capa enviada pelo autor:** a linha some, a auditoria fica e os dois arquivos somem, sem 403 no meio → teste na Task 2.

## Como rodar os testes

Igual ao plano da estante (Postgres descartável, como no CI):

```bash
docker run -d --name sv-test-db -e POSTGRES_PASSWORD=local-audit-test-only -p 5432:5432 postgres:16-alpine
PGPASSWORD=local-audit-test-only psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -f src/test/resources/schema-postgres.sql
STORYVERSE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/postgres ./mvnw -q test -Dtest=AdminIntegrationTest
```

Exige JDK 25. Sem JDK 25 ou Docker, verifique cada task fazendo push do branch e lendo o job "Verify API" do CI. **Nunca** aponte `STORYVERSE_TEST_DB_URL` para produção ou QA.

Branch: `feat/area-admin`, já criada a partir da `main`. Um commit por task.

---

### Task 1: V16 — papéis só em `user_roles` e `can_moderate()`

**Files:**
- Create: `src/main/resources/db/migration/V16__admin_roles.sql`
- Create: `src/test/java/com/storyverse/api/admin/AdminIntegrationTest.java`
- Modify: `src/main/java/com/storyverse/api/storage/StorageAccess.java` (método `admin` e os dois usos)
- Modify: `src/main/java/com/storyverse/api/community/CommunityService.java` (`isAdmin` → `canModerate`)
- Modify: `src/test/java/com/storyverse/api/SecurityIntegrationTest.java` (as duas linhas que inserem em `public.admins` passam a inserir `user_roles(id,'superadmin')`)

**Interfaces:**
- Produces:
  - SQL `public.can_moderate() returns boolean`;
  - `StorageAccess.canModerate(UUID user): boolean` e `StorageAccess.superadmin(UUID user): boolean`, ambos públicos (o `admin(UUID)` some);
  - `AdminIntegrationTest`, com o mesmo `@SpringBootTest`, `@EnabledIfEnvironmentVariable` e `@DynamicPropertySource` de `SecurityIntegrationTest`, mais `@AutoConfigureMockMvc` e estes helpers, usados por todas as tasks:
    - `AuthResponse user()` (signup com e-mail aleatório `@example.invalid`) e `UUID id(AuthResponse)`;
    - `UUID withRole(String role)` (cria conta e insere em `user_roles`);
    - `void unconfirmEmail(UUID)` (`update auth.users set email_confirmed_at=null`; com `require-email-confirmation=false`, o signup já nasce confirmado);
    - `int status(Executable)` (status da `AuthException`);
    - `<T> T asRuntime(UUID uid, Supplier<T>)` (abre `TransactionTemplate`, faz `set local role storyverse_api` e `DbSession.setUser`, e roda).

- [ ] **Step 1: Write the failing tests** em `AdminIntegrationTest`:

```java
@Test void legacyAdminsRowGrantsNothing() {
  UUID uid=id(user());
  jdbc.sql("insert into public.admins(user_id) values(:id)").param("id",uid).update();
  assertFalse(authz.isStaff(uid));
  assertEquals(Boolean.FALSE, rpc.call(uid,"is_admin",Map.of()));
}
@Test void moderatorReviewsAndReadsPendingSubmission() { /* autor cria envio (molde de deleteWaitingForAnApproval...) */
  // moderador: storage.readFor(mod, COMMUNITY_BOOKS, path).isPresent() == true
  // community.review(modToken, id, Map.of("status","approved")) não lança
}
@Test void supportCannotReviewSubmissions() {
  // assertEquals(403, status(() -> community.review(supportToken, id, Map.of("status","approved"))));
}
@Test void moderatorSeesOthersPendingUnderRls() {
  // asRuntime(mod, () -> count de book_submissions where status='pending' and user_id=<autor>) == 1
}
@Test void moderatorCannotDeleteOthersProfileMedia() {
  // assertFalse(storageAccess.deletable(mod, PROFILE_MEDIA, autor+"/livros/x.png"))
  // assertTrue(storageAccess.deletable(superadmin, PROFILE_MEDIA, autor+"/livros/x.png"))
}
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AdminIntegrationTest` (com `STORYVERSE_TEST_DB_URL`). Expected: FAIL (`legacyAdminsRowGrantsNothing` e os de moderador).

- [ ] **Step 3: Write `V16__admin_roles.sql`:**

```sql
-- Uma só fonte de papéis: user_roles. public.admins fica congelada (removida numa versão futura).
insert into public.user_roles (user_id, role)
select user_id, 'superadmin' from public.admins
on conflict do nothing;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin'); $$;

create or replace function public.is_staff()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin') or public.has_role('moderator') or public.has_role('support'); $$;

create or replace function public.can_moderate()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin') or public.has_role('moderator'); $$;
grant execute on function public.can_moderate() to anon, authenticated;

-- As políticas de book_submissions vêm do schema base (database/schema.sql) e usavam is_admin().
drop policy if exists "ver envios" on public.book_submissions;
create policy "ver envios" on public.book_submissions
  for select using (status = 'approved' or auth.uid() = user_id or public.can_moderate());

drop policy if exists "admin revisa" on public.book_submissions;
create policy "admin revisa" on public.book_submissions
  for update using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "remover envio" on public.book_submissions;
create policy "remover envio" on public.book_submissions
  for delete using (public.can_moderate() or (auth.uid() = user_id and status = 'pending'));
```

- [ ] **Step 4: Update `StorageAccess`.** `canModerate` faz `DbSession.setUser` + `select public.can_moderate()`, e `superadmin` faz o mesmo com `select public.has_role('superadmin')`. Em `readable` e no ramo `COMMUNITY_BOOKS` de `deletable`, use `canModerate`. No ramo `PROFILE_MEDIA .../livros/...`, use `superadmin`.

- [ ] **Step 5: Update `CommunityService`.** Renomeie `isAdmin` para `canModerate`, consultando `select public.can_moderate()`. Ajuste o comentário da classe (ele cita `supabase/community-books.sql`; passe a citar a V16).

- [ ] **Step 6: Atualize `SecurityIntegrationTest`** nas linhas que inserem em `public.admins` (hoje 147 e 338), trocando por `user_roles(...,'superadmin')`.

- [ ] **Step 7: Run** `./mvnw -q test -Dtest='AdminIntegrationTest,SecurityIntegrationTest'`. Expected: PASS.

- [ ] **Step 8: Verifique que não sobrou leitura de `admins`:** `grep -rn "public.admins\|from admins" src/main` deve trazer só V6, V16 e migrations antigas.

- [ ] **Step 9: Commit** — `feat(admin): papéis só em user_roles e can_moderate() (V16)`

---

### Task 2: Auditoria na mesma transação e arquivos apagados depois do commit

**Files:**
- Modify: `src/main/java/com/storyverse/api/feature/FeatureAccessService.java`
- Modify: `src/main/java/com/storyverse/api/feature/FeatureAdminController.java`
- Modify: `src/main/java/com/storyverse/api/community/CommunityService.java` (`updateCover`, `delete`)
- Modify: `src/main/java/com/storyverse/api/storage/StorageService.java`
- Test: `src/test/java/com/storyverse/api/admin/AdminIntegrationTest.java`

**Interfaces:**
- Consumes: helpers da Task 1.
- Produces:
  - `FeatureAccessService.grant(UUID actorId, UUID targetUserId, String feature, boolean enabled): void`, `@Transactional`, que faz `set(...)` e `authz.audit(actor,"feature.set","user",target,{feature,enabled})`. O controller `PUT` passa a chamar só `grant`;
  - `StorageService.removeAfterCommit(StorageBucket bucket, String path): void`, que registra uma `TransactionSynchronization.afterCommit` para apagar o arquivo (`safePath` + `Files.deleteIfExists`), com `log.warn` se falhar. Fora de transação, apaga na hora.

- [ ] **Step 1: Write the failing tests:**

```java
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
@Test void featureGrantIsAudited() { /* grant → 1 linha action='feature.set', target_id=target */ }
@Test void moderatorDeletesApprovedSubmissionWithUploadedCover() {
  // autor sobe texto + capa em <autor>/livros/c.png, envio aprovado com cover_url apontando para ela
  // community.delete(modToken, id) não lança
  // linha some; audit action='book_submission.delete' com actor=mod; storage.exists(...) false para os dois arquivos
}
@Test void ownerDeletingOwnPendingIsNotAudited() { /* 0 linhas de auditoria com target_id=id */ }
@Test void moderatorCoverChangeIsAuditedButOwnerIsNot() { /* action='book_submission.cover' */ }
```

- [ ] **Step 2: Run** `-Dtest=AdminIntegrationTest`. Expected: FAIL (`grant` não existe).

- [ ] **Step 3: Implement** `FeatureAccessService.grant` (injete `AuthzService`) e troque o `PUT` do controller para chamar `grant`.

- [ ] **Step 4: Implement `StorageService.removeAfterCommit`.** Em `CommunityService.delete`, troque os dois `storage.delete(...)` por `removeAfterCommit`, com a regra da Decisão 6, e audite `book_submission.delete` (`{title, status}`) quando quem age não é o dono. Em `updateCover`, audite `book_submission.cover` (`{cover_url}`) quando quem age não é o dono. Atualize o comentário "na mesma transação: se um arquivo falhar, a linha volta", que deixa de ser verdade.

- [ ] **Step 5: Run** `-Dtest='AdminIntegrationTest,SecurityIntegrationTest,CommunityControllerTest'`. Expected: PASS.

- [ ] **Step 6: Commit** — `fix(admin): auditoria na mesma transação e arquivos do envio apagados após o commit`

---

### Task 3: Contas — busca e detalhe

**Files:**
- Create: `src/main/java/com/storyverse/api/admin/EmailMask.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminUserRepository.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminUserService.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminUserController.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminUserSummary.java`, `AdminUserDetail.java`
- Test: `src/test/java/com/storyverse/api/admin/EmailMaskTest.java`, `AdminIntegrationTest.java`

**Interfaces:**
- Produces:
  - `EmailMask.mask(String email): String`;
  - `record AdminUserSummary(UUID id, String name, String emailMasked, boolean emailConfirmed, Instant createdAt, List<String> roles)`;
  - `record AdminUserDetail(UUID id, String name, String email, boolean emailConfirmed, Instant createdAt, Instant lastSignInAt, List<String> roles, List<String> features, int activeSessions, List<Map<String,Object>> recentAudit)`, com `recentAudit` nulo para quem não é superadmin;
  - `GET /api/v1/admin/users?q=` → `List<AdminUserSummary>`;
  - `GET /api/v1/admin/users/{id}` → `AdminUserDetail` (audita `user.view`);
  - `AdminUserService.requireAccountsAccess(UUID actor)`, que exige superadmin ou support. As Tasks 4 a 7 usam o mesmo padrão.

- [ ] **Step 1: Write `EmailMaskTest`** (teste unitário, sem banco):

```java
assertEquals("we***@gmail.com", EmailMask.mask("wernersaboia@gmail.com"));
assertEquals("a***@x.com", EmailMask.mask("ab@x.com"));
assertEquals("a***@x.com", EmailMask.mask("a@x.com"));
assertEquals("***", EmailMask.mask("semarroba"));
assertEquals("***", EmailMask.mask(null));
```

- [ ] **Step 2: Write the failing integration tests:**
  - `searchByExactEmailMasksTheEmail`: o support busca o e-mail do alvo em maiúsculas → 1 resultado com `emailMasked` sem o e-mail completo;
  - `searchByNameNeedsThreeChars`: `q="ab"` → 400; `q="%%%"` → 0 resultados (escape);
  - `searchByUuid`;
  - `moderatorCannotSearch`: 403;
  - `detailShowsRolesFeaturesSessionsAndAuditOnlyForSuperadmin`: superadmin → `recentAudit` não nulo, support → nulo, e `activeSessions >= 1` logo após o signup;
  - `detailIsAudited`: uma linha `user.view`;
  - `detailOfUnknownUserIs404`.
- [ ] **Step 3: Run.** Expected: FAIL.
- [ ] **Step 4: Implement**, seguindo as Decisões 1 a 3. Os papéis de cada resultado vêm numa só consulta (`array_agg`), sem N+1. O rate limit é aplicado só na busca.
- [ ] **Step 5: Run** `-Dtest='EmailMaskTest,AdminIntegrationTest'`. Expected: PASS.
- [ ] **Step 6: Commit** — `feat(admin): busca e detalhe de contas`

---

### Task 4: Contas — encerrar sessões e papéis

**Files:**
- Modify: `AdminUserService.java`, `AdminUserController.java`, `AdminUserRepository.java`
- Test: `AdminIntegrationTest.java`

**Interfaces:**
- Produces:
  - `POST /api/v1/admin/users/{id}/sessions/revoke` → `{ "revoked": <int> }` (superadmin ou support; audita `user.sessions.revoke` com `{count}`);
  - `PUT /api/v1/admin/users/{id}/roles/{role}` e `DELETE /api/v1/admin/users/{id}/roles/{role}` → `{ "roles": [...] }` (só superadmin; audita `role.grant` e `role.revoke` com `{role}`).

- [ ] **Step 1: Write the failing tests:**
  - `supportRevokesSessions`: depois, `auth.authenticate(tokenDoAlvo)` lança 401;
  - `cannotRevokeOwnSessions`: 403;
  - `superadminGrantsModeratorToConfirmedAccount` + auditoria;
  - `grantToUnconfirmedAccountIs409`;
  - `grantingSuperadminIs400` (e `user_roles` não muda);
  - `changingOwnRolesIs403` (inclui `DELETE` do próprio `moderator`);
  - `supportCannotChangeRoles`: 403;
  - `idempotentGrantDoesNotAudit`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement**, com as regras da seção 4.2 da spec e as Decisões 4 e 5. Ordem das checagens: papel do ator → `role` válido (400) → própria conta (403) → conta existe (404) → e-mail confirmado no `PUT` (409).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** — `feat(admin): encerrar sessões e dar/tirar papéis`

---

### Task 5: Features — catálogo e quem tem cada uma

**Files:**
- Create: `src/main/java/com/storyverse/api/admin/AdminCursor.java`
- Modify: `src/main/java/com/storyverse/api/feature/FeatureAccessService.java` (expor `static Set<String> known()`)
- Modify: `src/main/java/com/storyverse/api/feature/FeatureAdminController.java`
- Test: `AdminIntegrationTest.java`

**Interfaces:**
- Produces:
  - `GET /api/v1/admin/features/catalog` → `[{ "feature": "library", "enabledCount": n }]` (todas as conhecidas, mesmo com 0);
  - `GET /api/v1/admin/features/{feature}/users?cursor=` → `{ "items": [{ userId, name, emailMasked, grantedByName, updatedAt }], "nextCursor": string|null }`, 50 por página. Feature desconhecida → 400;
  - `AdminCursor.decode(String)`, `AdminCursor.from(Instant, UUID)` e `encode()`, no mesmo formato e com os mesmos erros de `ForumCursor`.
- Os dois endpoints são só para superadmin.

- [ ] **Step 1: Write the failing tests:**
  - `catalogCountsEnabledOnly` (uma conta ligada e uma desligada → 1);
  - `featureUsersPaginates` (55 contas → 50 + `nextCursor`, depois 5 e `null`);
  - `unknownFeatureIs400`;
  - `supportCannotSeeFeatures`: 403.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement**, com a Decisão 7.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** — `feat(admin): catálogo de features e contas por feature`

---

### Task 6: Visão geral

**Files:**
- Create: `src/main/java/com/storyverse/api/admin/AdminOverviewService.java`, `AdminOverviewController.java`, `AdminOverview.java`
- Test: `AdminIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/v1/admin/overview` → `AdminOverview(Moderation moderation, Accounts accounts, Map<String,Integer> features, Backup backup)`, com records aninhados:
  - `Moderation(int pendingSubmissions, int openReports)`;
  - `Accounts(int total, int newLast7Days)`;
  - `Backup(String status, Instant finishedAt, Long sizeBytes, boolean stale)`.

  Cada bloco vem `null` quando o papel do ator não pode vê-lo (seção 4.6 da spec). Quem não é staff → 403.

- [ ] **Step 1: Write the failing tests:**
  - `moderatorSeesOnlyModeration` (os outros três blocos nulos);
  - `supportSeesOnlyAccounts`;
  - `superadminSeesAll`;
  - `backupIsStaleWithoutRecentSuccess` (insere um `backup_runs` com sucesso de 30 h atrás → `stale=true`; um de 1 h atrás → `false`);
  - `plainUserIs403`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement**, com as Decisões 9 e 10.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** — `feat(admin): visão geral por papel`

---

### Task 7: Auditoria

**Files:**
- Create: `src/main/java/com/storyverse/api/admin/AdminAuditService.java`, `AdminAuditController.java`
- Test: `AdminIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/v1/admin/audit?action=&actorId=&targetType=&targetId=&from=&to=&before=` → `{ "items": [{ id, actorId, actorName, action, targetType, targetId, metadata, createdAt }], "nextBefore": long|null }`. `actorName` é nulo quando `actorId` é nulo (script). Só superadmin.

- [ ] **Step 1: Write the failing tests:**
  - `auditFiltersByActionPrefix` (`action=forum.`);
  - `auditPaginatesByBefore` (55 linhas → 50 + `nextBefore`);
  - `auditDateRangeIsInclusiveInSaoPaulo` (uma linha às 23:30 de São Paulo do dia `to` entra no resultado);
  - `scriptRowsHaveNullActor`;
  - `moderatorCannotReadAudit`: 403;
  - `invalidDateIs400`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement**, com a Decisão 8. Filtros opcionais montados com parâmetros nomeados, nunca com concatenação de valores do usuário.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** — `feat(admin): consulta da auditoria`

---

### Task 8: Matriz de permissões, 401 e documentação

**Files:**
- Create: `src/test/java/com/storyverse/api/admin/AdminControllerTest.java`
- Modify: `src/test/java/com/storyverse/api/admin/AdminIntegrationTest.java`
- Modify: `README.md` (seção "Endpoints")

- [ ] **Step 1: `AdminControllerTest`** (MockMvc em H2, molde de `ForumControllerTest`): cada rota nova de `/api/v1/admin/**` sem `Authorization` → 401.
- [ ] **Step 2: Matriz em `AdminIntegrationTest`.** O teste `@ParameterizedTest permissionMatrix` percorre a tabela da seção 3 da spec. Para cada (papel, endpoint), chama via MockMvc com o Bearer de uma conta só com esse papel, mais uma conta sem papel, e confere 2xx ou 403 conforme a tabela. Os endpoints de fórum entram só com a leitura de filas, que é 2xx para support.
- [ ] **Step 3: Run** a suíte inteira: `./mvnw --batch-mode verify` (com `STORYVERSE_TEST_DB_URL`). Expected: tudo verde.
- [ ] **Step 4: README.** Adicione em "Endpoints" um bloco `Admin` com cada rota, o papel exigido e uma linha de comportamento, no estilo do bloco da estante.
- [ ] **Step 5: Commit** — `test(admin): matriz de permissões e documentação das rotas`
- [ ] **Step 6: Push** do branch, PR para `main`, e confira o job "Verify API". Depois do merge, publique no QA pelo workflow existente.
