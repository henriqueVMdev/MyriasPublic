# Refactor FastAPI → Java (Spring Boot) — diário e decisões

Documento de acompanhamento da reescrita do backend do **ML Manager** (Python/FastAPI)
para **Java/Spring Boot**. Registra o que foi descoberto, as decisões tomadas e o
que ainda falta. Objetivo: **aprendizado/portfólio** — por isso a reescrita é
**incremental, uma fatia vertical de cada vez**, não um big-bang.

---

## 1. Contexto do projeto original

App de gestão de anúncios do **Mercado Livre** para revenda de autopeças, com duas
contas conectadas (HRBIMPORTS e HRBAUTOPARTS).

| Camada     | Original                                              |
|------------|-------------------------------------------------------|
| Backend    | FastAPI + SQLAlchemy async + httpx (~11.4k linhas)    |
| Frontend   | Vue 3 + Vite + Tailwind + Pinia                       |
| Banco      | **PostgreSQL** (serviço `db` no Docker, porta 5432)   |
| Migrations | Alembic                                               |
| Infra      | Docker Compose com hot-reload                         |

---

## 2. Decisões da reescrita

| Decisão            | Escolha                  | Por quê                                                                 |
|--------------------|--------------------------|-------------------------------------------------------------------------|
| Escopo             | **Só backend**           | Frontend Vue fala HTTP com qualquer backend; reescrevê-lo não traz ganho |
| Frontend           | **Reaproveitado**        | Copiado pra `frontend/` sem alterações (só ajuste de proxy)             |
| Framework Java     | **Spring Boot 3**        | Padrão de mercado; mapeia 1:1 com o que o FastAPI já fazia              |
| Java               | **21 (LTS)**             | —                                                                       |
| Build              | **Maven**                | Mais comum em tutoriais/aprendizado                                      |
| Banco (prod)       | **PostgreSQL**           | Mesma stack do original                                                  |
| Banco (dev)        | **H2 em memória**        | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` roda sem infra     |
| Migrations         | `ddl-auto` por enquanto  | Hibernate cria as tabelas a partir das entidades; trocar por Flyway depois |
| Segurança          | só `spring-security-crypto` | Precisamos só do BCrypt; o starter completo imporia login/filtro próprios |
| Repo               | **separado**, sem git nosso | Usuário commita com a própria conta → histórico limpo no GitHub        |

> **Atenção registrada:** reescrever um app que já funciona é alto custo / valor
> duvidoso. Aqui se justifica **só** por ser aprendizado/portfólio. Se a motivação
> fosse performance, o gargalo é a **API do Mercado Livre (rede)**, não o Python.

---

## 3. Fatia 1 — Autenticação (FEITA)

Login do painel: `usuário/senha` → cookie de sessão assinado (HMAC, stateless) →
porteiro nas rotas `/api/*` → bootstrap do admin inicial.

### Mapeamento FastAPI → Spring

| FastAPI (Python)                          | Spring (Java)                                          |
|-------------------------------------------|--------------------------------------------------------|
| `main.py` (app + middleware + rotas /app) | `MlManagerApplication` + `AppAuthFilter` + `AuthController` |
| `AppAuthMiddleware`                        | `AppAuthFilter` (`OncePerRequestFilter`)               |
| `_make/_verify_session_token` (HMAC)      | `SessionTokenService`                                  |
| `models/user.py` (`AppUser` SQLAlchemy)   | `auth/AppUser` (`@Entity` JPA)                         |
| `services/user_service.py`                | `UserAccountService` + `UserRepository`               |
| `permissions.py`                          | `Permissions`                                          |
| `config.py` (`Settings` pydantic)         | `application.yml` + `@Value`                           |
| `Depends(get_db)` / sessão async          | `UserRepository` (Spring Data, sem boilerplate)        |
| `Column(JSON)` (lista de permissões)      | `StringListJsonConverter` (`AttributeConverter`)       |
| `scripts/create_admin.py`                 | `DevSeeder` (só no profile dev)                        |

### Descobertas / detalhes que importam

- **Token de sessão é stateless** (não é dict em memória). Esquema mantido idêntico
  ao Python: `<userId>.<issued>.<hmacSHA256>`, assinado com `app.secret-key`.
  Sobrevive a restart do servidor.
- **Comparação da assinatura em tempo constante** (`MessageDigest.isEqual`) pra não
  vazar a assinatura por timing — espelha o `hmac.compare_digest` do Python.
- **Bootstrap:** enquanto não existe nenhum usuário, o painel fica liberado (pra
  criar o admin inicial). O filtro e o `/session` replicam isso.
- **Permissões** são uma lista de chaves (seções + ações + métricas). No Postgres o
  original usa coluna JSON; aqui guardamos como **JSON em coluna texto** via
  `AttributeConverter` — portável entre Postgres e H2, sem depender de `jsonb`.
- **Rate limit de login:** 5 tentativas / 5 min por IP, em memória. Suficiente pra
  1 instância; se escalar pra réplicas, trocar por Redis.
- **CORS** com `allowCredentials(true)` e origem fixa (frontend) — o cookie de
  sessão só viaja assim.

### Como rodar / verificar

```bash
mvn test                                              # testa a lógica do token (sem banco)
mvn spring-boot:run -Dspring-boot.run.profiles=dev    # sobe na :8000 com H2 + admin/admin
curl -i -X POST http://localhost:8000/api/app/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'
```

---

## 4. Próximas fatias (PENDENTES)

Ordem sugerida — cada uma é uma entrega vertical completa:

1. **Cliente do Mercado Livre (OAuth + refresh de token)** — base de tudo. Já há
   placeholders de config (`meli.*`) no `application.yml`. Mapear `services/meli_auth.py`
   e `meli_client.py` (httpx → `RestClient`/`WebClient`), persistir `MeliToken`,
   `PkceState`, `AuthEvent`.
2. **Items / Performance** — leitura de anúncios e métricas.
3. **Bulk** — edição em massa.
4. **Clone** — inclui Playwright (existe `playwright-java`).
5. **Promoções**, **Perguntas/Mensagens**, **Dashboard**, **Logs de operação**.

### Pegadinhas da API do ML já conhecidas (do projeto original)

Vão valer igual no Java quando portar o cliente. Resumo do que está documentado no
`CLAUDE.md` do projeto Python:

- **Medidas de embalagem (PACKAGE_*):** 3 famílias de IDs conforme o contexto; remap
  obrigatório no POST (`seller_package_*` minúsculo); ML exige inteiros (arredondar).
- **Compatibilidades (autopeças):** item ligado a UserProduct exige rota
  `/user-products/{id}/compatibilities`; `item_to_copy` retorna 200 como ack, não
  confirmação → **sempre ler de volta** após ~0.8s; não copia POSITIONS.
- **Posições (POSITION):** atributo abstrato; só funciona em `update:`, não em `create:`.
- **SKU:** POST ignora `seller_custom_field`; setar via PUT depois, em ambos os campos.
- **Rate limiting:** 429 **e** 423 significam "espera"; backoff exponencial.
- **MLBU (produto unificado):** cadeia scrape público → API → Playwright; antibot cresceu.

> Esses detalhes só importam quando a fatia do cliente ML for portada — registrados
> aqui pra não se perderem.
