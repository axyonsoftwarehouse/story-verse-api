# Story Verse API

Template inicial de uma API REST independente usando Spring Boot. O projeto contém apenas a aplicação, um endpoint de health check e um teste de exemplo; não implementa funcionalidades de livros, integração com provedores externos, autenticação ou persistência.

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

## Endpoint inicial

- `GET /api/v1/health`: confirma que a aplicação está respondendo e retorna o status e horário da verificação. O endpoint pode ser usado como health check pelo container ou por um provedor de hospedagem.

O frontend Story Verse continua responsável pelas funcionalidades de livros. Endpoints de negócio só devem ser adicionados aqui quando houver uma necessidade de backend definida.

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
