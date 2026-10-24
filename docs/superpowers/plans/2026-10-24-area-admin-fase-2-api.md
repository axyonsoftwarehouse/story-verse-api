# Área do admin — Fase 2 — API

> Plano de implementação da API. O plano do web é `story-magic/docs/superpowers/plans/2026-10-24-area-admin-fase-2.md`.

**Goal:** entregar as fases 2A (contas), 2B (sanções), 2C (métricas) e 2D (exportação de auditoria) da API, deixando financeiro/assinatura para a fase de pagamentos.

**Architecture:** controller fino → service `@Transactional` (`auth.authenticate` → `authz.requireRole`/`requireStaff` → `DbSession.setUser` → repositório `JdbcClient`) → SQL explícito. Toda ação que muda estado grava `authz.audit` na mesma transação. A RLS continua valendo para o papel `storyverse_api`.

**Tech Stack:** Java 25, Spring Boot, JdbcClient, Flyway, PostgreSQL 16, JUnit/MockMvc.

**Spec:** `story-magic/docs/superpowers/specs/2026-10-23-area-admin-fase-2.md` (sem a seção 9/financeiro).

## Global Constraints

- Financeiro e assinatura ficam fora: nada de `subscriptionStatus`, filtro/coluna de assinatura, `/admin/financeiro` ou MRR nesta fase. A área financeira atual não é alterada.
- Papéis: `superadmin`, `moderator`, `support`; ninguém cria/remove `superadmin` pela API.
- Contas: a lista sempre mascara o e-mail; o detalhe mostra o e-mail completo e é auditado (`user.view`). A busca/lista não é auditada.
- Sanções: só superadmin aplica/revoga; staff (superadmin, moderator, support) vê o histórico. Ninguém aplica sanção em si mesmo nem em conta com papel de staff.
- `warning` não bloqueia; `suspension`/`ban` bloqueiam login, refresh e qualquer requisição autenticada (código `ACCOUNT_SANCTIONED`).
- Sanções expiradas deixam de valer sem job; a consulta sempre compara `expires_at` com `now()`.
- Métricas: números agregados, sem PII; superadmin apenas; cache de 5 minutos por intervalo.
- Auditoria: exportação somente leitura, no máximo 31 dias e 50.000 linhas; registrar `audit.export` com ator, filtros e quantidade.
- Migração nova é `V18__admin_phase2.sql` (a última é `V17_3`).
- Erros em português com `AuthException(status, mensagem, code?)`; JSON camelCase e `Instant` ISO-8601.
- Nunca logar e-mail completo; não editar `src/test/resources/schema-postgres.sql` para tabelas de aplicação.

## Decisões deste plano (lacunas da spec)

1. **Lista de contas em endpoint próprio.** `GET /api/v1/admin/users?q=` continua existindo (lista simples, compatível com o web atual). A lista nova com filtros e cursor fica em `GET /api/v1/admin/users/list`; o bloco inicial em `GET /api/v1/admin/users/recent`. Assim a API pode subir antes do web sem quebrar a busca antiga.
2. **Sem assinatura.** `AdminUserSummary`/`AdminUserDetail` não ganham `subscriptionStatus`/`subscription` nesta fase.
3. **Métricas via funções de agregação `security definer`.** `reading_days` e `library_entries` são fechadas por RLS; em vez de abrir SELECT para o runtime, a V18 cria funções que devolvem só agregados (sem `user_id`) e concede EXECUTE a `storyverse_api`.
4. **Retenção:** Dn = conta da coorte que teve pelo menos um dia com `reading_days.seconds > 0` entre o cadastro e o dia do cadastro + n (inclusive). Coorte por semana de cadastro; o rótulo no web deixa claro “leu nos primeiros N dias”.
5. **DAU/leitores ativos:** `count(distinct user_id)` de `reading_days` com `seconds > 0` no dia. “Contas ativas” por sessão não entra no primeiro corte para não somar duas definições.
6. **Exportação CSV em streaming:** `HttpServletResponse` + `JdbcTemplate.query` com `RowCallbackHandler`. Delimitador `;`, BOM UTF-8, campos com aspas quando necessário; `createdAt` em ISO-8601 UTC.
7. **Sanção bloqueante com corpo próprio:** `AccountSanctionedException` devolve `403` com `{error, code: "ACCOUNT_SANCTIONED", kind, reason, expiresAt}`.
8. **Aviso (`warning`) no login:** `AuthView` ganha `activeWarning` (nulo quando não há). O web mostra o banner e o descarta; a central de notificações fica para a Fase 3.

## Review Focus

- **Sanção no login/refresh/autenticação:** a checagem roda antes de criar sessão e antes de tocar `last_sign_in_at`; `warning` nunca bloqueia.
- **Staff como alvo:** aplicar sanção em superadmin/moderator/support e em si mesmo devolve 403; revogar sanção de outra conta não mexe em papéis.
- **Expiração:** suspensão vencida libera login/API mesmo sem job; banimento só libera após revogação.
- **Sessões:** aplicar suspensão/ban revoga as sessões na mesma transação.
- **Privacidade da lista:** nenhum e-mail completo na lista/recent; detalhe completo só em `GET /users/{id}` com auditoria.
- **Cursor:** `recent` não pagina; `/list` e `/sanctions` usam cursor keyset e limite máximo de 50.
- **Métricas:** sem `user_id`/e-mail no JSON; consultas dentro do período; cache não vaza entre atores (só superadmin acessa).
- **CSV:** 31 dias/50.000 linhas; `audit.export` registra filtros e quantidade mesmo quando o CSV vem vazio.

## Como rodar os testes

```bash
docker run -d --name sv-test-db -e POSTGRES_PASSWORD=local-audit-test-only -p 5432:5432 postgres:16-alpine
PGPASSWORD=local-audit-test-only psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -f src/test/resources/schema-postgres.sql
STORYVERSE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/postgres ./mvnw -q test -Dtest=AdminIntegrationTest
```

Um método: `./mvnw -q test -Dtest=AdminIntegrationTest#nomeDoMetodo`.

---

## Task 1: Migração V18 (índices e funções de métricas)

**Files**
- Create: `src/main/resources/db/migration/V18__admin_phase2.sql`
- Create: `src/test/java/com/storyverse/api/admin/AdminPhase2MigrationTest.java` (ou cobrir no AdminIntegrationTest)

**Interfaces**
- Índices: `auth.users (created_at desc, id)`, `auth.users (last_sign_in_at)`, `user_sanctions (created_at desc, id)` (a V6 já tem os dois primeiros de sanções; só completar o que faltar).
- `admin_list_accounts()` não é função: a lista usa SQL direto no repositório.
- Funções (todas `security definer`, `set search_path = public`, `revoke execute from public, anon, authenticated`, `grant execute to storyverse_api`):
  - `admin_metrics_daily(p_from date, p_to date) returns table(day date, active_readers bigint, seconds bigint, books_started bigint, books_finished bigint, entries_created bigint)`
  - `admin_metrics_accounts(p_from date, p_to date) returns table(total bigint, new7 bigint, new30 bigint, confirmed bigint, staff bigint)`
  - `admin_metrics_features() returns table(feature text, enabled bigint)`
  - `admin_metrics_retention(p_from date, p_to date) returns table(cohort_week date, cohort_size bigint, d1 bigint, d7 bigint, d30 bigint)`
- As funções de leitura usam `public.reading_days`, `public.library_entries`, `auth.users`, `public.user_roles` e `public.feature_access`, e nunca devolvem `user_id`.

**Steps**
- [ ] Escrever a migração com `create index if not exists` e as funções `create or replace`.
- [ ] Garantir que `storyverse_api` execute as funções (grant) e que `anon/authenticated` não.
- [ ] Rodar `./mvnw -q -DskipTests package` para validar o SQL no boot do teste.
- [ ] Teste no Postgres de teste: chamar cada função com período vazio e conferir colunas/zero linhas.
- [ ] Commit: `feat(admin): migracao da fase 2 e funcoes de metricas`

**Verify:** Flyway aplica V18 em banco limpo e em banco que já tem V1..V17_3.

---

## Task 2: Contas recentes e lista paginada (2A)

**Files**
- Modify: `src/main/java/com/storyverse/api/admin/AdminUserController.java`
- Modify: `src/main/java/com/storyverse/api/admin/AdminUserService.java`
- Modify: `src/main/java/com/storyverse/api/admin/AdminUserRepository.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminUserPage.java`
- Modify: `AdminUserSummary.java` (acrescentar `lastSignInAt` se ainda não vier)
- Modify: `AdminIntegrationTest.java`

**Interfaces**
- `GET /api/v1/admin/users/recent?limit=20` → `List<AdminUserSummary>` (máx. 50, default 20), ordem `created_at desc, id desc`.
- `GET /api/v1/admin/users/list?q=&role=&feature=&cursor=&limit=20` → `AdminUserPage(List<AdminUserSummary> items, String nextCursor)`.
  - `q`: UUID exato, senão parte do e-mail ou do nome (mínimo 3 caracteres; comportamento do BUG-005).
  - `role`: `superadmin|moderator|support` exato; `feature`: nome exato e `enabled`.
  - `cursor`: `AdminCursor` (`epochMillis:uuid`) sobre `(created_at, id)`.
  - `limit`: 1..50; busca +1 para saber se há próxima página.
  - Sem `subscription`.
- `AdminUserSummary`: `id`, `name`, `emailMasked`, `emailConfirmed`, `createdAt`, `lastSignInAt`, `roles`.
- Permissões: `superadmin` ou `support`; rate limit da busca continua por ator.
- Não auditar lista/busca; detalhe continua auditado.

**Steps**
- [ ] Escrever teste vermelho: recent mascarado e ordenado; `/list?q=` acha parte do e-mail; filtro de papel; filtro de feature; cursor devolve a próxima página sem repetir; limite máximo; moderator recebe 403.
- [ ] Implementar `recent` e `list` no repositório (SQL com `exists` para role/feature e keyset).
- [ ] Reaproveitar `AdminCursor` e `EmailMask`.
- [ ] Rodar `AdminIntegrationTest` e `AdminControllerTest`.
- [ ] Commit: `feat(admin): contas recentes e lista paginada`

**Verify:** os testes novos e os antigos de busca/detalhe continuam verdes; a busca antiga `GET ?q=` não muda.

---

## Task 3: Sanções — modelo, serviço e bloqueio no auth (2B parte 1)

**Files**
- Create: `src/main/java/com/storyverse/api/admin/Sanction.java` (record)
- Create: `src/main/java/com/storyverse/api/admin/SanctionRepository.java`
- Create: `src/main/java/com/storyverse/api/admin/SanctionService.java`
- Create: `src/main/java/com/storyverse/api/admin/AccountSanctionedException.java`
- Modify: `src/main/java/com/storyverse/api/auth/AuthService.java` (login, refresh, requireSession)
- Modify: `src/main/java/com/storyverse/api/auth/AuthResponse.java` / `AuthView.java` (campo `activeWarning`)
- Modify: `src/main/java/com/storyverse/api/common/ApiExceptionHandler.java` (corpo da sanção)
- Modify: `AdminUserDetail.java` e `AdminUserService.detail` (histórico resumido)
- Modify: `AdminIntegrationTest.java`

**Interfaces**
- `SanctionService.activeBlocking(UUID userId)` → `Sanction` ou nulo. Consulta `kind in ('suspension','ban') and revoked_at is null and (expires_at is null or expires_at > now())`, a mais recente.
- `SanctionService.activeWarning(UUID userId)` → `Sanction` ou nulo (`kind='warning'`, não revogada, não expirada).
- `SanctionService.history(UUID userId, int limit)` → lista para o detalhe.
- Cache de 30 s por usuário; `invalidate(userId)` em aplicar/revogar.
- Em `AuthService.login`: depois de validar senha/e-mail e antes de `touchLastSignIn`, chamar `activeBlocking`; se houver, lançar `AccountSanctionedException`.
- Em `AuthService.refresh` e `AuthService.requireSession`: mesma checagem; toda requisição autenticada de conta suspensa/banida devolve 403.
- `AccountSanctionedException` carrega `kind`, `reason`, `expiresAt`; handler devolve `{error, code, kind, reason, expiresAt}`.
- `AuthView`/`AuthResponse` ganham `SanctionNotice activeWarning` (nulo quando não há). O login com `warning` funciona normalmente.
- `User_sanctions` exige `DbSession.setUser(jdbc, userId)` antes da consulta (política self), inclusive em login/refresh.
- `AdminUserDetail.sanctions` = últimos 20 registros (tipo, motivo, criado em, criado por, expira em, revogado em/por).

**Steps**
- [ ] Teste vermelho: aviso não bloqueia login e aparece em `activeWarning`; suspensão bloqueia login com 403 e corpo `ACCOUNT_SANCTIONED`; suspensão expirada não bloqueia; banimento bloqueia; refresh e requisição autenticada também bloqueiam; `warning` não bloqueia API.
- [ ] Implementar repositório/serviço e a checagem no auth; invalidar cache ao aplicar/revogar.
- [ ] Ajustar fixtures de `AdminUserDetail` nos testes existentes.
- [ ] Rodar `AdminIntegrationTest` e testes de auth.
- [ ] Commit: `feat(admin): sancoes bloqueiam login e api`

**Verify:** login de `qa-suspenso` (quando o seed existir) devolve 403 com motivo; `qa-sem-estante` com warning entra e recebe o aviso.

---

## Task 4: Sanções — endpoints administrativos (2B parte 2)

**Files**
- Create: `src/main/java/com/storyverse/api/admin/SanctionController.java`
- Modify: `SanctionService.java` (apply/revoke/list)
- Modify: `AdminIntegrationTest.java`

**Interfaces**
- `POST /api/v1/admin/users/{id}/sanctions` — corpo `{kind, reason, expiresAt?}`; só superadmin.
  - Validações: `reason` obrigatório; `suspension` exige `expiresAt` futuro; `ban` não aceita `expiresAt`; `warning` aceita `expiresAt` opcional.
  - Alvo: não pode ser o próprio ator; conta com qualquer papel de staff → 403.
  - Suspensão/ban: `SessionRepository.revokeAllForUser` na mesma transação.
  - Audita `sanction.create` com `{kind, expiresAt, reason}`.
- `POST /api/v1/admin/sanctions/{id}/revoke` — corpo `{reason?}`; só superadmin; audita `sanction.revoke` com `{reason}`; idempotente em sanção já revogada? -> 400.
- `GET /api/v1/admin/users/{id}/sanctions` — staff; histórico paginado simples (20).
- `GET /api/v1/admin/sanctions?status=active|revoked|all&kind=&cursor=&limit=20` — staff; cursor `AdminCursor` sobre `(created_at, id)`; devolve também `userName`/`emailMasked` para a tela.
- Respostas camelCase; nunca devolver e-mail completo na lista de sanções.

**Steps**
- [ ] Testes de permissão (moderator/support não aplicam; staff vê; superadmin aplica/revoga).
- [ ] Testes de validação e de alvo (self, staff, superadmin).
- [ ] Testes de revogação de sessões e de auditoria (create/revoke).
- [ ] Testes de lista (status/kind/cursor) e de expiração.
- [ ] Implementar controller/service/repositório.
- [ ] Commit: `feat(admin): endpoints de sancoes`

**Verify:** aplicar suspensão em conta comum revoga sessões; revogar libera o login; auditoria mostra os dois eventos.

---

## Task 5: Métricas de produto (2C)

**Files**
- Create: `src/main/java/com/storyverse/api/admin/AdminMetricsController.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminMetricsService.java`
- Create: `src/main/java/com/storyverse/api/admin/AdminMetrics*.java` (records)
- Modify: `AdminIntegrationTest.java`

**Interfaces**
- `GET /api/v1/admin/metrics/overview?from=YYYY-MM-DD&to=YYYY-MM-DD` (default últimos 30 dias; máximo 90) → `{accounts, reading, features, retention}`.
  - `accounts`: `total`, `new7`, `new30`, `confirmed`, `staff`.
  - `reading`: `activeReaders`, `minutes`, `booksStarted`, `booksFinished`, `shelfEntries`.
  - `features`: mapa `feature -> enabled`.
  - `retention`: lista de `{cohortWeek, cohortSize, d1, d7, d30}` (percentuais inteiros).
- `GET /api/v1/admin/metrics/reading?from=&to=&group=day|week` → série `{bucket, activeReaders, minutes, booksStarted, booksFinished}`.
- `GET /api/v1/admin/metrics/retention?from=&to=` → mesma lista do overview (período maior).
- Só superadmin; `DbSession.setUser(actor)`.
- Cache em memória de 5 min por `from|to|group`; `ConcurrentHashMap` com timestamp; limpar entradas antigas no acesso.

**Steps**
- [ ] Testes vermelhos: sem token 401, moderator 403, superadmin 200; números coerentes com dados inseridos no teste (reading_days/library_entries/feature_access); sem `user_id`/e-mail no JSON; período inválido 400; cache devolve o mesmo objeto e não vaza para outro período.
- [ ] Implementar as consultas chamando as funções da V18 + counts de feature/contas.
- [ ] Definir o período default e o limite de 90 dias.
- [ ] Commit: `feat(admin): metricas de produto`

**Verify:** no QA, com dados de teste, os cards batem com as consultas SQL manuais; a tela não mostra PII.

---

## Task 6: Exportação CSV da auditoria (2D)

**Files**
- Modify: `src/main/java/com/storyverse/api/admin/AdminAuditController.java`
- Modify: `src/main/java/com/storyverse/api/admin/AdminAuditService.java`
- Modify: `AdminIntegrationTest.java`

**Interfaces**
- `GET /api/v1/admin/audit/export?action=&actorId=&targetType=&targetId=&from=&to=` — superadmin.
- Validações: `from`/`to` obrigatórios; intervalo máximo 31 dias; `to >= from`; caso contrário 400.
- Limite de 50.000 linhas; se ultrapassar, 400 com mensagem pedindo período menor (não corta silenciosamente).
- CSV `text/csv;charset=UTF-8`, `Content-Disposition: attachment; filename="auditoria-AAAA-MM-DD.csv"`, BOM UTF-8, cabeçalho `id;createdAt;actor;action;targetType;targetId;metadata`, campos com `;`/aspas escapados.
- Antes de escrever: contar linhas com os filtros, auditar `audit.export` com `{filters, count}` e só então transmitir.
- Usar `JdbcTemplate.query` com `RowCallbackHandler` para escrever no `OutputStream` (sem carregar 50k linhas na memória).
- Reaproveitar o parser de filtros atual (não duplicar regras de data/action).

**Steps**
- [ ] Testes: sem token 401; moderator/support 403; intervalo > 31 dias 400; CSV com BOM/`;`/escape; `audit.export` gravado; filtro de ação/data respeitado; 50.001 linhas 400 (teste com limite reduzido por configuração de teste ou dados sintéticos).
- [ ] Implementar o endpoint e o streaming.
- [ ] Commit: `feat(admin): exporta auditoria em csv`

**Verify:** download no QA abre no Excel pt-BR com acentos; `audit.export` aparece na auditoria com a quantidade.

---

## Ordem de deploy

1. Task 1 (migração) junto da Task 2 no primeiro PR de API.
2. Tasks 3 e 4 no PR de sanções.
3. Task 5 no PR de métricas.
4. Task 6 no PR de auditoria.

Cada PR sobe para `develop` e é testado no QA antes do próximo. O web acompanha cada PR na mesma fase.
