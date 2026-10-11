# story-verse-api

Backend REST independente em Spring Boot para o Story Verse. Este repositório contém o serviço da API, sem código do frontend.

## Destino e limites da migração

O backend foi transferido do módulo `story-magic-api/` do monorepo `codeplusofc/story-magic` para a raiz deste repositório independente `axyonsoftwarehouse/story-verse-api`. Os endpoints e o formato de resposta foram preservados para clientes HTTP existentes. A configuração Render usa a raiz do repositório, sem `rootDir` aninhado.

O catálogo usa `InMemoryBookRepository`; não há driver, URL, credenciais ou configuração de banco neste projeto. Não foi criada uma conexão de banco fictícia. `/api/v1/health` verifica apenas se a aplicação HTTP está respondendo, não a disponibilidade de um banco. Os dados de demonstração são reiniciados com a aplicação.

## Organização

- `catalog/api`: endpoints REST e formato de resposta do catálogo.
- `catalog/application`: regras de busca do catálogo.
- `catalog/application/BookCatalogRepository`: porta de persistência do catálogo.
- `catalog/domain`: modelo de livro independente do formato HTTP.
- `catalog/infrastructure`: adaptador de catálogo em memória, substituível por um adaptador de banco sem alterar controladores ou regras de negócio.
- `integrations/gutenberg`: adaptador HTTP para obter textos públicos do Project Gutenberg, com timeout e espelho de contingência.
- `integrations/openlibrary`: cliente HTTP para busca de metadados públicos e capas da Open Library.
- `common/api`: conversão de erros de negócio e de integração para respostas HTTP padronizadas.
- `system/api`: endpoints operacionais, como verificação de saúde.
- `config`: configuração HTTP, incluindo CORS para o frontend local.
- `src/main/resources/application-{dev,prod}.yaml`: configurações separadas por ambiente; segredos são injetados por variáveis do ambiente de execução.
- `ApiApplication.java`: inicialização do Spring Boot.

## Executar localmente

Requisitos: JDK 25 ou superior. Execute os comandos a partir da raiz do repositório. No IntelliJ IDEA, abra `pom.xml` como projeto Maven e aguarde o carregamento das dependências. A classe principal é:

```text
com.story_magic.api.ApiApplication
```

Abra `src/main/java/com/story_magic/api/ApiApplication.java` e clique no triângulo verde ao lado de `main` → **Run 'ApiApplication.main()'**. O perfil `dev` é ativado por padrão e aceita CORS apenas das origens locais do Vite. O diretório compilado fica em `%USERPROFILE%\.story-verse-build\api`, para permitir executar mesmo se o caminho do repositório contiver caracteres especiais.

Para iniciar a API pelo wrapper Maven:

```powershell
.\mvnw.cmd spring-boot:run
```

No Git Bash ou Linux/macOS, use `./mvnw spring-boot:run`. Para executar os testes e verificações:

```bash
./mvnw --batch-mode verify
```

No PowerShell, o comando equivalente é `.\mvnw.cmd --batch-mode verify`. O wrapper baixa Maven 3.9.16 na primeira execução e reutiliza a distribuição no cache local; é necessário acesso à internet nessa primeira execução. As variáveis podem ser definidas na seção **Environment variables** da Run Configuration ou na sessão do terminal, por exemplo `$env:SPRING_PROFILES_ACTIVE = "dev"`. `.env.example` é apenas uma referência; Spring Boot não carrega esse arquivo automaticamente. Nunca grave credenciais reais nesse arquivo ou no Git.

## Ambientes

- `dev` é o perfil padrão para execução local. `CORS_ALLOWED_ORIGINS` pode substituir as origens locais padrão.
- `prod` requer `CORS_ALLOWED_ORIGINS` explícita, ativa logs estruturados ECS em JSON e usa o mesmo endpoint de health para as verificações do provedor de hospedagem.
- `PORT` é lida pelo servidor; localmente assume `8080`, em produção o provedor injeta a porta.
- O desligamento do servidor é gracioso. Não há segredos externos necessários para os endpoints atuais.

Ative produção com `SPRING_PROFILES_ACTIVE=prod`. Defina valores secretos diretamente nas configurações do ambiente de execução/hospedagem, nunca em `application-prod.yaml`, imagens Docker ou variáveis `VITE_*`.

## API e health check

Com o servidor iniciado, abra:

```text
http://localhost:8080/api/v1/health
```

A resposta inclui `status: "ok"` e um timestamp. A porta padrão `8080` pode ser alterada com a variável `PORT`.

## Catálogo de livros

- `GET /api/v1/books`: lista o catálogo.
- `GET /api/v1/books?q=iracema`: busca sem diferenciar maiúsculas/minúsculas por título, autor, gênero ou sinopse.
- `GET /api/v1/books/gb-67740`: consulta um livro pelo identificador; retorna `404` se não existir.
- `GET /api/v1/books/gb-67740/content`: obtém o texto público integral do livro no Project Gutenberg. Responde como `text/plain; charset=UTF-8`, remove as marcações de licença do Gutenberg e tenta o espelho oficial se o site principal falhar.
- `GET /api/v1/external-books?q=crepusculo`: pesquisa metadados/capas na Open Library para livros fora do catálogo local. Retorna até seis sugestões; prioriza edição em português e mantém as regras de ano, número de edições e relevância que antes estavam no frontend.

A resposta é uma lista JSON com `id`, `gutenbergId`, `title`, `author`, `genre`, `blurb`, `coverUrl`, `textLanguage` e `shelf`. Os identificadores e campos principais seguem o formato de `Ebook` usado pelo frontend. O adaptador atual do catálogo é em memória; os dados de demonstração voltam ao padrão quando a API reinicia. O serviço depende de `BookCatalogRepository`, permitindo adicionar uma implementação de persistência sem acoplar essa mudança à API HTTP.

O CORS permite por padrão `http://localhost:5173` e `http://127.0.0.1:5173`. Configure `CORS_ALLOWED_ORIGINS` com uma lista separada por vírgulas ao usar outros endereços. As rotas e o formato dos livros permanecem disponíveis para consumo HTTP pelo frontend; este projeto não contém o código do frontend para validar a configuração de URL usada por ele.

Erros seguem o formato `application/problem+json`, com título, detalhe, status HTTP e timestamp. Falhas em provedores externos retornam `502`; timeout retorna `504`; consultas inválidas retornam `400`. O endpoint de texto aceita somente IDs presentes no catálogo da API.

Esta versão fornece uma base REST somente de leitura. Ainda não há persistência ou endpoints de conta/login. O login continua sob responsabilidade do Supabase, e o frontend permanece desacoplado. Antes de adicionar dados pessoais ou operações de escrita, implementar autorização (por exemplo, validar access tokens JWT emitidos pelo Supabase) e uma estratégia de armazenamento permanente.

As rotas atuais só expõem metadados públicos, textos em domínio público e busca de catálogo. Elas não substituem a autorização do Supabase nem devem ser usadas para dados privados. Erros conhecidos são convertidos para `application/problem+json`; erros inesperados são registrados no log do servidor e retornam uma mensagem genérica, sem stack trace ou detalhes internos ao cliente.

Para persistência futura, implemente uma segunda versão de `BookCatalogRepository` com Spring Data/JDBC e configuração de datasource via variáveis de ambiente. Não adicione banco até serem definidos os dados que precisam persistir, migrações e política de backup. A pasta `integrations/` é a fronteira para adaptadores de IA; novas chaves de provedores devem ser lidas apenas de variáveis secretas do backend.

## Docker e deploy contínuo

A imagem Docker usa um estágio Maven de build, uma imagem JRE de runtime e executa como usuário não privilegiado. O build executa os testes (`verify`) antes de gerar a imagem:

```bash
docker build -t story-verse-api .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e CORS_ALLOWED_ORIGINS=https://SEU-FRONTEND.example \
  story-verse-api
```

Execute esses comandos na raiz deste repositório. O workflow que existia no monorepo de origem não faz parte do módulo transferido; configure CI neste repositório separadamente se necessário.

### Render (always-on)

`render.yaml` descreve um serviço Docker **Starter** pago, com região Ohio, deploy automático após mudanças no Git e health check em `/api/v1/health`. O plano Starter é escolhido para evitar o modo de suspensão dos serviços gratuitos; a cobrança é feita pela Render.

Para publicar:

1. Envie o repositório para GitHub e conecte a conta GitHub ao Render.
2. No Render, crie um **Blueprint** e selecione o arquivo `render.yaml` na raiz.
3. Informe a origem pública exata do frontend para `CORS_ALLOWED_ORIGINS` quando o Render solicitar. Não use `*`.
4. Crie o serviço e aguarde o deploy terminar. O endereço HTTPS aparecerá no painel; teste `<URL-do-serviço>/api/v1/health`.
5. Confirme que o health check está verde e que o auto deploy está habilitado. O serviço passa a ser atualizado a cada push na branch conectada.

A configuração de infraestrutura está pronta no repositório, mas o serviço ainda não está publicado: concluir esses passos exige acesso à conta Render e ao repositório GitHub, que não estão disponíveis neste ambiente. Escolha consciente de custo: o plano Starter é pago e pode ser alterado no Blueprint/painel.

## CI e validação

Execute a suíte completa com `.\mvnw.cmd --batch-mode verify` no PowerShell ou `./mvnw --batch-mode verify` no Git Bash/Linux/macOS. O workflow `.github/workflows/ci.yml` roda a mesma verificação com JDK 25 em cada push e pull request; o Dockerfile também executa `verify` antes de montar a imagem de produção.

A suíte cobre os contratos HTTP e respostas de erro, CORS permitido e negado, regras do catálogo e sugestões, validação de busca, chamadas de provedores externos, fallbacks, timeouts, respostas inválidas e preservação da interrupção do thread. Testes dos clientes HTTP usam um servidor local efêmero; não dependem da disponibilidade da Open Library ou do Project Gutenberg nem de credenciais externas. JaCoCo gera um relatório HTML em `%USERPROFILE%\\.story-verse-build\\api\\site\\jacoco\\index.html` (ou `$HOME/.story-verse-build/api/site/jacoco/index.html`) e faz o build falhar abaixo de 90% de cobertura de instruções ou 75% de branches.
