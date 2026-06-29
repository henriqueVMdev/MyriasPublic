# ML Manager — Backend Java

Reescrita do backend FastAPI (`ML_manager/backend`) em **Spring Boot 3 + Java 21**.
O frontend **Vue 3 + Vite** (em `frontend/`) é o mesmo do projeto original — não muda,
só fala HTTP com este backend.

## Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, proxia /api -> localhost:8000
```

Feito de forma **incremental, uma fatia vertical por vez**. Esta primeira entrega
cobre só a **autenticação do painel** (login → token de sessão → rota protegida).

## Como rodar (sem Postgres, banco em memória)

Precisa de **JDK 21** e **Maven** instalados.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Sobe em `http://localhost:8000`. No profile `dev` um usuário **admin / admin** é
criado automaticamente (H2 em memória, some ao reiniciar).

Testar o login:

```bash
curl -i -X POST http://localhost:8000/api/app/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
```

Rodar só o teste de lógica do token (não precisa de banco):

```bash
mvn test
```

## Mapeamento FastAPI → Spring (referência de estudo)

| FastAPI (Python)                          | Spring (Java)                                   |
|-------------------------------------------|-------------------------------------------------|
| `main.py` (app + middleware + rotas /app) | `MlManagerApplication` + `AppAuthFilter` + `AuthController` |
| `AppAuthMiddleware`                        | `AppAuthFilter` (`OncePerRequestFilter`)        |
| `_make/_verify_session_token` (HMAC)      | `SessionTokenService`                           |
| `models/user.py` (`AppUser` SQLAlchemy)   | `auth/AppUser` (`@Entity` JPA)                  |
| `services/user_service.py`                | `UserAccountService` + `UserRepository`         |
| `permissions.py`                          | `Permissions`                                   |
| `config.py` (`Settings` pydantic)         | `application.yml` + `@Value`                    |
| `Depends(get_db)` / sessão async          | `UserRepository` (Spring Data, sem boilerplate) |
| `Column(JSON)` (lista de permissões)      | `StringListJsonConverter` (`AttributeConverter`)|
| `scripts/create_admin.py`                 | `DevSeeder` (só no profile dev)                 |
| `services/meli_auth.py` (OAuth PKCE)      | `meli/MeliAuthService` + `MeliToken`/`PkceState`/`AuthEvent` |
| `services/meli_client.py` (httpx+retry)   | `meli/MeliClient` (`RestClient`)                |
| `utils/rate_limiter.py` (asyncio)         | `meli/MeliRateLimiter` (`Semaphore` síncrono)   |
| `api/auth.py` (rotas OAuth)               | `meli/MeliAuthController` (`/api/auth`)         |
| `api/webhooks.py`                         | `meli/WebhookController`                        |
| `require_permission(...)`                 | `auth/PanelSecurity`                            |
| `services/meli_items.py`                  | `meli/MeliItemsService`                         |
| `api/items.py`                            | `meli/ItemsController` (`/api/items`)           |
| `schemas/items.py`                        | `meli/ItemUpdate` + records no controller       |
| `models/operation_log.py`                 | `ops/OperationLog` + `OperationLogRepository`   |
| `contextvars` (ator da req.)              | `ops/CurrentActor` (`ThreadLocal`)              |
| `Column(JSON)` (payload/response)         | `config/JsonNodeConverter`                      |

O token de sessão usa o **mesmo esquema** do Python: `<userId>.<issued>.<hmacSHA256>`,
assinado com `app.secret-key`.

## Banco de produção

`application.yml` aponta pro Postgres por padrão (mesma stack do projeto original).
Sobrescreva por variáveis de ambiente: `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`,
`APP_SECRET_KEY`, `FRONTEND_URL`.

## Fatia 2 — OAuth + cliente Mercado Livre

Fluxo OAuth com PKCE (`/api/auth/login` → `/callback`), múltiplas contas (até 4),
refresh proativo de token e o `MeliClient` com retry/backoff/rate-limit que as
próximas fatias usam para falar com a API do ML. Configurar via env:
`MELI_APP_ID`, `MELI_SECRET_KEY`, `MELI_REDIRECT_URI`.

## Fatia 3 — Items (anúncios)

Listagem com filtros (incl. status especial `pending`, varrido client-side),
detalhe do item (+descrição/variações), edição (título/preço/atributos/status/
descrição/fotos) com verificação de atributos silenciosamente rejeitados pelo ML,
upload de imagem e atributos de categoria. Cada edição grava um `OperationLog`.
Aqui o `MeliClient` ganhou `multiGetItems` (paralelo via `ExecutorService`, com o
rate limiter como teto real), `scanAllItems` e `uploadPicture`.

## Próximas fatias (ainda não portadas)

performance, bulk, clone (Playwright), promoções, perguntas/mensagens,
dashboard, logs de operação.
