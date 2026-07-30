# ML Manager

Painel de operação para vendedores do Mercado Livre com **múltiplas contas**: um lugar
para acompanhar vendas, editar anúncios em massa, gerenciar promoções, responder
compradores e analisar concorrência — com um assistente de IA que executa essas
operações sob confirmação humana.

Backend em **Java 21 + Spring Boot 3.3.5**, frontend em **Vue 3 + Vite + TypeScript**,
PostgreSQL em produção e H2 em memória no perfil de desenvolvimento.

> Repositório público, mantido aberto para leitura. Nenhuma credencial acompanha o
> código — veja [`.env.example`](.env.example).

---

## O que o sistema faz

| Área | Capacidades |
|------|-------------|
| **Dashboard** | Resumo consolidado multi-conta, vendas, faturamento, reputação e séries de performance por período |
| **Anúncios** | Listagem com filtros, detalhe com descrição e variações, edição de título/preço/atributos/status/fotos, upload de imagem |
| **Performance** | Análise sobre snapshots (inventário, vendas, visitas, ads), detecção de anúncios duplicados |
| **Edição em massa** | Atualização por SKU em uma ou várias contas, descrição multi-conta, compatibilidades e posições de categoria |
| **Clonagem** | Preview e criação de anúncios em uma ou múltiplas contas, incluindo catálogo (MLBU), medidas e atributos obrigatórios |
| **Promoções** | Promoções do vendedor, inclusão/remoção de anúncios, cupons |
| **Atendimento** | Perguntas em anúncios e mensagens pós-venda, com histórico unificado |
| **Concorrência** | Comparação de um anúncio com rivais, inspeção do vencedor, descoberta de tendências por categoria |
| **Qualidade** | Estado de qualidade do anúncio no catálogo do ML |
| **Auditoria** | Toda operação de escrita gera registro com ator, payload, resposta e agrupamento por lote |
| **Assistente de IA** | Agente com ferramentas reais sobre os dados do painel; qualquer alteração exige confirmação explícita |

Permissões são granulares e por chave: seções controlam acesso a páginas, ações
controlam operações destrutivas (`bulk_edit`, `manage_promotions`, `delete_listing`…)
e métricas escondem dado financeiro sensível.

---

## Decisões de arquitetura

As escolhas abaixo são as que moldaram o sistema e que eu defenderia numa revisão de código.

### O agente de IA nunca escreve sozinho

Ferramentas de leitura o modelo executa direto. Ferramentas de escrita **não executam**:
produzem um `pending_action` com resumo legível e TTL, devolvido ao usuário como um card
de confirmação. Só o clique do usuário dispara a execução.

Três detalhes que fazem isso ser seguro de verdade:

- **A permissão é revalidada na confirmação**, não no momento em que o modelo propôs.
  Se o acesso do usuário mudou no intervalo, a ação morre ali.
- **O resumo vem do mesmo parsing que a execução usa** — o que o usuário confirma é
  exatamente o que roda, sem risco de a descrição divergir do efeito.
- **Ferramentas são filtradas por permissão antes de chegarem ao modelo.** Quem não
  pode editar em massa não tem `bulk_update_items` no catálogo enviado ao LLM: o modelo
  não sabe que ela existe, então não a propõe. E o loop rejeita qualquer ferramenta que
  não foi oferecida naquela requisição — defesa contra o modelo alucinar um nome.

`ai/AiToolRegistry.java`, `ai/PendingActionStore.java`, `ai/AiController.java`

### Custo de LLM é tratado como recurso finito

O agente roda em loop (modelo ⇄ ferramentas), então **um comando pode virar várias
chamadas pagas**. Três limites atuam juntos:

- `max_tokens` por resposta e histórico do cliente truncado — o corpo da requisição é
  entrada não confiável; sem corte, quem chama a API escolhe quantos tokens de prompt
  são pagos, multiplicado pelo número de iterações.
- Teto de gasto diário **verificado antes da chamada**, por usuário e global.
- Auditoria com custo real em USD, tokens e `generation_id` por comando, vindos do
  provedor — não estimados.

O teto usa contador em memória em vez de consultar o banco a cada requisição, e a razão
é a que importa: **o custo só é conhecido depois que a resposta volta**, e a linha de
auditoria só é gravada no fim do comando inteiro. Um `SUM(cost)` por iteração leria
sempre o saldo anterior ao comando, e todas as chamadas do mesmo loop passariam. O banco
é lido uma única vez, no startup, para o teto sobreviver a um restart.

`ai/AiQuotaService.java`, `ai/AiAuditService.java`, `ai/AiAssistantService.java`

### O cliente da API externa carrega o conhecimento do domínio

Todo acesso ao Mercado Livre passa por um único cliente, e o retry distingue as falhas
em vez de tratar tudo como "erro":

| Código | Significado real | Tratamento |
|--------|------------------|------------|
| `401` | Token expirado | Invalida o cache e força refresh em **uma** retentativa |
| `429` | Rate limit | Backoff exponencial |
| `423` | Recurso travado — comum em exclusões no mesmo `user_product` | Backoff |
| `409` | Conflito de versão no KVS, comum ao editar anúncios irmãos da mesma família em sequência | Backoff; a próxima tentativa costuma passar |
| `5xx` | Falha do servidor | Backoff |

O rate limiter combina um semáforo (concorrência) com intervalo mínimo entre chamadas
(vazão), e o pool de threads do cliente é dimensionado **pelo mesmo número** do semáforo:
passar disso só criaria threads paradas esperando permissão.

Leituras em lote vão paralelas com o limiter como teto real, e quando o `multiget` do ML
devolve um item sem os campos pedidos, há refetch individual — comportamento observado
na API, não suposto.

`meli/MeliClient.java`, `meli/MeliRateLimiter.java`

### Sessão stateless, assinada

O token é `<userId>.<emissão>.<HMAC-SHA256>`, assinado com a chave da aplicação. O id do
usuário viaja dentro do payload assinado, então o servidor sabe quem é sem guardar estado
— sobrevive a restart sem store de sessão nem sticky session. A comparação da assinatura é
em tempo constante, para não vazar o segredo por timing, e a emissão é verificada para a
sessão expirar.

A aplicação **se recusa a subir** fora do perfil de desenvolvimento se a chave de
assinatura não for definida: com uma chave previsível, qualquer pessoa forjaria o cookie
de um administrador. É o tipo de falha que precisa quebrar o boot, não virar aviso no log.

`auth/SessionTokenService.java`, `auth/AppAuthFilter.java`

### Multi-conta com OAuth PKCE e refresh proativo

Até 4 contas do Mercado Livre conectadas simultaneamente, cada uma com seu token. O fluxo
usa PKCE (`S256`) e o token é renovado com margem antes de expirar, em vez de esperar o
`401` — o `401` continua tratado, mas como rede de segurança.

`meli/MeliAuthService.java`, `meli/MeliToken.java`, `meli/PkceState.java`

### Operações pesadas fora do ciclo da requisição

A varredura completa do catálogo para gerar snapshots é O(N) sobre a API externa e não
cabe num request HTTP. Roda em background com guarda contra execuções empilhadas para a
mesma conta; a interface consulta o estado do snapshot e busca visitas e ads sob demanda.

`perf/PerfRefreshRunner.java`, `perf/PerfSnapshot.java`

### Edição em massa que sobrevive ao mundo real

O que a experiência com a API exigiu, e que está no código:

- Campos aceitos são **lista de permitidos**, não lista de bloqueados — um payload
  inesperado não vira update acidental.
- Quando o ML rejeita um campo como não atualizável, a operação **retenta sem aquele
  campo** e informa o que foi pulado, em vez de falhar o lote inteiro.
- Substituir fotos preservando a capa é uma opção explícita, porque a ordem das imagens
  tem efeito comercial.
- Cada lote recebe um `batch_id`, então uma edição de centenas de anúncios é auditável
  como uma unidade.

`meli/MeliBulkService.java`, `ops/OperationLog.java`

---

## Stack

**Backend** — Java 21, Spring Boot 3.3.5, Spring Data JPA, `RestClient`, PostgreSQL
(H2 em memória no perfil `dev`), Maven.
**Frontend** — Vue 3, Vite 6, TypeScript 5.7, Axios.
**Integrações** — API do Mercado Livre (OAuth 2.0 + PKCE), OpenRouter para o assistente.

Schema gerado pelas entidades JPA. Sem gerador de código, sem framework de mock nos
testes além de Mockito e do servidor HTTP de teste do Spring.

---

## Rodando localmente

Requer **JDK 21**, **Maven** e **Node 20+**.

```bash
cp .env.example .env      # preencha o que for usar
```

Backend, com banco em memória:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

No PowerShell o argumento precisa ir entre aspas, senão o `-D` é interpretado pelo shell:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Sobe em `http://localhost:8000`. No perfil `dev` um usuário `admin` / `admin` é criado
automaticamente e o banco desaparece ao reiniciar.

Fora do perfil `dev` a aplicação exige `APP_SECRET_KEY` definida e se recusa a subir sem
ela — proposital, ver [Sessão stateless](#sessão-stateless-assinada).

Frontend:

```bash
cd frontend
npm install
npm run dev               # http://localhost:5173, faz proxy de /api para :8000
```

Testes:

```bash
mvn test
```

As funcionalidades do Mercado Livre exigem uma conta conectada via OAuth
(`MELI_APP_ID`, `MELI_SECRET_KEY`, `MELI_REDIRECT_URI`). O assistente de IA exige
`OPENROUTER_API_KEY`. Sem essas credenciais o painel sobe e navega, mas sem dados.

---

## Estrutura

```
src/main/java/com/hrb/mlmanager/
  auth/         sessão assinada, usuários, permissões
  meli/         cliente da API do ML, OAuth, anúncios, bulk, clone, promoções, mensagens
  ai/           agente, catálogo de ferramentas, teto de gasto, auditoria
  dashboard/    métricas consolidadas multi-conta
  perf/         snapshots e análise de performance
  competition/  análise de concorrência
  quality/      qualidade de anúncio no catálogo
  ops/          log de operações
  config/       conversores JPA, CORS, seed de desenvolvimento
frontend/src/
  api/          camada HTTP tipada por domínio
  pages/        telas
  components/   componentes reutilizáveis
  composables/  lógica de tela reaproveitável
  stores/       estado (sessão, permissões)
  router/       rotas com guarda de permissão por seção
```
