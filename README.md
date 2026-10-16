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
- `POST /api/v1/auth/signup` — `{ "email", "password", "name?" }` → cria a conta e devolve os tokens.
- `POST /api/v1/auth/login` — `{ "email", "password" }` → devolve os tokens.
- `POST /api/v1/auth/refresh` — `{ "refreshToken" }` → rotaciona e devolve tokens novos.
- `POST /api/v1/auth/logout` — `{ "refreshToken"? }` (ou cabeçalho `Authorization`) → encerra a sessão.
- `GET /api/v1/auth/me` — cabeçalho `Authorization: Bearer <accessToken>` → dados da conta.
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
- `GET /api/v1/presence/{room}?key=…` — presença por **SSE** (público): envia `event: count` com
  `{"count": n}` sempre que a contagem da sala muda. Só o número circula.
- `GET /api/v1/ai/status` — se a IA está configurada no servidor (público).
- `POST /api/v1/ai/reply` — gera a resposta do personagem/sugere elenco, com as **chaves no servidor**
  (exige sessão). Corpo: `{ "system", "messages": [{role, content}], "maxTokens" }` → `{ "text" }`.
- `GET /api/v1/me/reading-state` — dados de leitura da conta (progresso, marcações, conversas…).
- `PUT /api/v1/me/reading-state` — grava de forma otimista: `{ "data": {…}, "baseUpdatedAt": <do GET|null> }`
  → `{ "updated": bool, "updatedAt": … }` (`updated=false` se outro aparelho gravou antes).

A sessão usa **Bearer token**: access de 30 min + refresh de 30 dias, opacos e guardados apenas como
hash (SHA-256). O frontend Story Verse continua responsável pelas funcionalidades de livros.

## Banco de dados

Usa o Postgres do stack `storyverse` (schema base em `database/schema.sql`). As sessões do backend
vivem em `auth.sessions`, criada pela migração Flyway em `src/main/resources/db/migration`. O schema
base precisa existir antes (ele cria `auth.users`). Configuração por variáveis de ambiente:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/storyverse
SPRING_DATASOURCE_USERNAME=storyverse
SPRING_DATASOURCE_PASSWORD=...
```

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
