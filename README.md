# Story Verse API

API REST independente do Story Verse (Spring Boot). Hoje implementa o health check e o **módulo de contas** (cadastro, login, sessão e perfil). As funcionalidades de livros seguem no frontend.

## Requisitos

- JDK 25 ou superior.
- Acesso à internet na primeira execução para baixar Maven 3.9.16 pelo wrapper.

## Executar localmente

Na raiz do repositório, inicie a aplicação:

```powershell
.\mvnw.cmd spring-boot:run
```

No Git Bash ou Linux/macOS:

```bash
./mvnw spring-boot:run
```

A API inicia na porta `8080` por padrão. Para escolher outra porta, defina a variável `PORT` no ambiente do processo (por exemplo, `$env:PORT = "8081"` no PowerShell). O arquivo `.env.example` documenta essa variável, mas não é carregado automaticamente pelo Spring Boot.

## Endpoints

- `GET /api/v1/health`: confirma que a aplicação está respondendo.
- `POST /api/v1/auth/signup` — `{ "email", "password", "name?" }` → cria a conta; devolve o access token e define o cookie de refresh (quando a confirmação de e-mail está desligada).
- `POST /api/v1/auth/login` — `{ "email", "password" }` → devolve o access token e define o cookie de refresh.
- `POST /api/v1/auth/refresh` — sem corpo (ou `{ "refreshToken" }` para clientes legados) → rotaciona o cookie e devolve um access token novo.
- `POST /api/v1/auth/logout` — sem corpo (ou `{ "refreshToken"? }`, ou cabeçalho `Authorization`) → encerra a sessão e limpa o cookie.
- `GET /api/v1/auth/me` — cabeçalho `Authorization: Bearer <accessToken>` → dados da conta.
- `GET /api/v1/account/export` — cabeçalho `Authorization` → perfil, estado de leitura e envios (JSON).
- `DELETE /api/v1/account` — `{ "password" }` + `Authorization` → apaga conta, arquivos e dados pessoais; exige a senha atual.
- `POST /api/v1/rpc/{fn}` — chama uma função do banco em nome do usuário (diamantes, ranking e
  acervo), ex.: `my_wallet`, `weekly_ranking`, `spin_daily_wheel`, `is_admin`, `set_submission_cover`.
  Lista branca no servidor.
- `GET /api/v1/community/submissions?scope=approved|mine|pending` — acervo da comunidade (aprovados
  dispensam sessão).
- `POST /api/v1/community/submissions` — envia um livro (sessão; o `user_id` vem da sessão).
- `PATCH /api/v1/community/submissions/{id}` — revisa (admin, com `status`) ou troca a capa (dono/admin).
- `DELETE /api/v1/community/submissions/{id}` — apaga (admin, ou dono enquanto pendente).
- `POST|GET|DELETE /api/v1/storage/{bucket}/object?path=…` — arquivos (com sessão): `profile-media`
  (fotos e capas), `user-books` (livros importados), `community-books` (texto do acervo). Cada um só
  na própria pasta.
- `GET /api/v1/storage/public/{bucket}/{path}` — leitura **sem sessão** de bucket público (`profile-media`).
- **Admin** (staff; a API confere o papel em cada rota: sem sessão → 401, sem papel → 403):
  - `GET /api/v1/admin/overview` — visão geral (superadmin, moderator, support).
  - `GET /api/v1/admin/users?q=` — busca por UUID, e-mail exato ou parte do nome; e-mail mascarado (superadmin, support).
  - `GET /api/v1/admin/users/{id}` — detalhe da conta; auditoria recente só para superadmin (superadmin, support).
  - `POST /api/v1/admin/users/{id}/sessions/revoke` — encerra as sessões da conta (superadmin, support).
  - `PUT|DELETE /api/v1/admin/users/{id}/roles/{role}` — dá/tira `moderator` ou `support` (superadmin; nunca a própria conta nem `superadmin`).
  - `GET /api/v1/admin/features?userId=` — features de uma conta (superadmin).
  - `GET /api/v1/admin/features/catalog` — features conhecidas e contagem (superadmin).
  - `GET /api/v1/admin/features/{feature}/users` — quem tem a feature, com cursor (superadmin).
  - `PUT /api/v1/admin/features` — `{ "userId", "feature", "enabled" }`; audita na mesma transação (superadmin).
  - `GET /api/v1/admin/audit` — auditoria filtrável e paginada, só leitura (superadmin).
  - `GET /api/v1/admin/forum/reports|topics|replies` — filas de moderação do fórum (staff).
  - `PATCH /api/v1/admin/forum/reports|topics|replies/{id}` — resolve ou modera (superadmin, moderator).
- `GET /api/v1/presence/{room}?key=…` — presença por **SSE** (público): envia `event: count` com
  `{"count": n}` sempre que a contagem da sala muda. Só o número circula.
- `GET /api/v1/ai/status` — se a IA está configurada no servidor (público).
- `POST /api/v1/ai/reply` — gera a resposta do personagem/sugere elenco, com as **chaves no servidor**
  (exige sessão). Corpo: `{ "system", "messages": [{role, content}], "maxTokens" }` → `{ "text" }`.
- `GET /api/v1/me/reading-state` — dados de leitura da conta (progresso, marcações, conversas…).
- `PUT /api/v1/me/reading-state` — grava de forma otimista: `{ "data": {…}, "baseUpdatedAt": <do GET|null> }`
  → `{ "updated": bool, "updatedAt": … }` (`updated=false` se outro aparelho gravou antes).
- Estante (biblioteca pessoal), só para contas com a feature `library` (sem ela: 403 `FEATURE_DISABLED`).
  `{bookKey}` é `gb:<n>`, `cm:<uuid>` ou `local:<id>`.
  - `GET /api/v1/me/library` — lista; filtros `status` (repetível ou CSV), `author`, `series`, `language`,
    `genre`, `year`, `minRating`, `maxRating`, `sort` (`recent`, `title`, `author`, `rating`, `finished`),
    `cursor`, `limit` (até 100) → `{ "items": [...], "nextCursor" }`. Com `updatedSince=<updatedAt>`
    devolve o que mudou depois, **inclusive apagadas** (`deletedAt`), para o sync.
  - `GET /api/v1/me/library/stats?year=` — números do ano.
  - `GET /api/v1/me/library/{bookKey}` — uma entrada (404 se fora da estante).
  - `PUT /api/v1/me/library/{bookKey}` — cria ou substitui a entrada inteira; para substituir, mande
    `baseUpdatedAt` igual ao `updatedAt` atual.
  - `PATCH /api/v1/me/library/{bookKey}` — muda só os campos enviados; `baseUpdatedAt` obrigatório.
  - `DELETE /api/v1/me/library/{bookKey}` — remove (soft delete) → 204.
  - `POST /api/v1/me/library/{bookKey}/finish` — `{ finishedOn?, rating?, review? }`.
  - `POST /api/v1/me/library/{bookKey}/abandon` — `{ dnfReason, dnfNote? }`.
  - Erros: 400 dados inválidos, 401 sem sessão, 403 sem a feature, 404 fora da estante, 409 conflito
    (`{ "error", "code": "CONFLICT", "current": <entrada atual> }`), 422 regra de negócio (nota, datas,
    motivo, estante cheia), 429 mais de 60 escritas por minuto.

A sessão usa **Bearer token** para o access de 30 min e um **refresh de 30 dias em cookie HttpOnly,
Secure e SameSite** (nome `storyverse_refresh`, caminho `/api/v1/auth`). Os dois são opacos e o banco
guarda apenas o hash SHA-256; o refresh token não aparece no JSON de login/refresh. O frontend Story
Verse continua responsável pelas funcionalidades de livros.

## Banco de dados

Usa o Postgres do stack `storyverse` (schema base em `database/schema.sql`). As sessões do backend
vivem em `auth.sessions`, criada pela migração Flyway em `src/main/resources/db/migration`. O schema
base precisa existir antes (ele cria `auth.users`). Configuração por variáveis de ambiente:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/storyverse
SPRING_DATASOURCE_USERNAME=storyverse_api
SPRING_DATASOURCE_PASSWORD=...
```

O runtime deve usar `storyverse_api` (sem ownership, superuser ou bypassrls). Com
`APP_REQUIRE_RUNTIME_ROLE=true` (padrão), a API falha o boot se o usuário do banco puder ignorar a
RLS. As migrações usam credenciais administrativas separadas em `SPRING_FLYWAY_*`.

## Testes

Execute os testes e empacote a aplicação:

```powershell
.\mvnw.cmd --batch-mode verify
```

No Git Bash ou Linux/macOS, use `./mvnw --batch-mode verify`. O teste de exemplo verifica a inicialização da aplicação e a resposta do endpoint de health.

## Container e CI

O `Dockerfile` compila e testa a aplicação antes de criar uma imagem de runtime que executa com usuário não privilegiado:

```bash
docker build -t story-verse-api .
docker run --rm -p 8080:8080 story-verse-api
```

O workflow `.github/workflows/ci.yml` executa `verify` com JDK 25 em cada push e pull request. O endpoint `/api/v1/health` pode ser usado como health check quando um provedor de hospedagem for escolhido.

## Contas na API propria

Endpoints de confirmacao, recuperacao e perfil:

- `POST /api/v1/auth/confirm-email` - `{ "token" }` -> sessao (o link confirma e ja entra).
- `POST /api/v1/auth/resend-confirmation` - `{ "email" }` -> 202, sempre resposta generica.
- `POST /api/v1/auth/forgot-password` - `{ "email" }` -> 202, sempre resposta generica.
- `POST /api/v1/auth/reset-password/validate` - `{ "token" }` -> 204 se o link ainda vale; 400 se expirou/foi usado (nao consome o token).
- `POST /api/v1/auth/reset-password` - `{ "token", "password" }` -> 204; revoga as sessoes.
- `POST /api/v1/auth/change-password` - `{ "currentPassword", "newPassword" }` com Bearer -> 204.
- `PATCH /api/v1/auth/profile` - `{ "name", "avatar", "photo", "cover" }` com Bearer -> usuario atualizado.

Com `APP_AUTH_REQUIRE_EMAIL_CONFIRMATION=true` (padrao em producao), o cadastro responde 202
sem tokens e envia um link por SMTP. Com `false`, responde 201 com sessao (util em testes locais).
As migracoes Flyway V5 criam `auth.email_tokens` e os grants do papel `storyverse_api`.