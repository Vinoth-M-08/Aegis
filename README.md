# Aegis: LLM Gateway & AI Traffic Control

> An OpenAI-compatible gateway that sits between your applications and LLM providers, adding semantic caching, per-tenant token budgets, provider failover, PII redaction, and prompt-injection detection, with full cost and latency tracing.

![Status](https://img.shields.io/badge/status-in%20progress-orange)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Why Aegis?

Teams that call LLM APIs directly from every service run into the same problems:

| Problem | What Aegis does |
| --- | --- |
| Paying repeatedly for near-identical prompts | **Semantic cache** returns stored answers for semantically similar prompts (pgvector similarity search) |
| One team or tenant burns the whole monthly budget | **Per-tenant token budgets** enforced atomically with Redis Lua scripts |
| Provider outage or rate limit takes the product down | **Automatic failover** across providers with circuit breakers and retries |
| Sensitive data leaving the network inside prompts | **PII redaction** (emails, phone numbers, card numbers, IDs) before the prompt is forwarded |
| Users trying to override system instructions | **Prompt-injection detection** with weighted rule-based scoring |
| No visibility into who spends what | **Per-request tracing** of tokens, cost, latency, cache hits, and provider used |

Applications keep using the standard OpenAI SDK. They only change the base URL.

---

## Architecture

```mermaid
flowchart LR
    C[Client apps<br/>OpenAI SDK] -->|/v1/chat/completions| G[Aegis Gateway<br/>Spring Boot WebFlux]

    subgraph Pipeline [Request pipeline]
        direction TB
        A1[1. Auth and tenant resolution<br/>API key to tenant]
        A2[2. Rate limit<br/>Redis sliding window]
        A3[3. Prompt-injection guard]
        A4[4. Budget reservation<br/>Redis Lua, atomic]
        A5[5. PII redaction]
        A6[6. Semantic cache lookup<br/>pgvector]
        A7[7. Provider router<br/>retries, circuit breakers, failover]
        A1 --> A2 --> A3 --> A4 --> A5 --> A6 --> A7
    end

    G --> A1
    A6 -- cache hit --> G
    A7 --> P1[OpenAI]
    A7 --> P2[Azure OpenAI]
    A7 --> P3[Gemini / Ollama / any<br/>OpenAI-compatible API]

    A2 <--> R[(Redis<br/>budgets, rate limits)]
    A4 <--> R
    A6 <--> V[(PostgreSQL + pgvector<br/>prompt embeddings, responses)]
    G --> T[(PostgreSQL<br/>tenants, usage ledger, audit log)]
    G --> O[OpenTelemetry / Prometheus<br/>Grafana, Jaeger]
```

### Request lifecycle

1. **Authenticate**: the bearer API key is hashed and resolved to a tenant and its policy (allowed models, budgets, rate limit, guard mode, redaction, cache settings). Lookups are cached for 30 seconds; admin changes evict them immediately.
2. **Rate limit**: a sliding-window Lua script enforces requests per minute per tenant. Over the limit returns `429` with `Retry-After`.
3. **Guard**: user and tool messages are scored against prompt-injection rules. Depending on policy the request is blocked (`400`), flagged (forwarded with `X-Aegis-Guard: flagged`), or not checked. Blocked and flagged requests go to the audit log.
4. **Reserve budget**: a Lua script checks the tenant's daily and monthly budgets (and the key's own daily budget, if set) and reserves the estimated tokens in one atomic step, so concurrent requests cannot overspend.
5. **Redact**: PII is replaced with typed placeholders (`<EMAIL_1>`, `<PHONE_1>`). The audit log records counts per type, never values.
6. **Cache lookup**: the redacted conversation is embedded and searched in pgvector within the same tenant, model, and system prompt plus parameter scope. Above the similarity threshold, the cached answer is returned without calling a provider.
7. **Route**: on a miss, the highest-priority provider that serves the model is called. Transient errors are retried with exponential backoff and jitter; repeated failures open the provider's circuit breaker, and the request fails over to the next provider.
8. **Restore, settle and record**: placeholders in the answer are restored, the budget reservation is corrected to the provider's real token count, and cost, latency, cache status, and provider are written to the usage ledger and emitted as metrics and traces.

---

## Features

### Semantic caching
- Embeds the redacted, normalized conversation and stores it with the response in **pgvector** (HNSW index, cosine distance)
- Configurable similarity threshold, globally and per tenant
- Cache entries are scoped by tenant, model, and a hash of the system prompt and generation parameters, so tenants never see each other's data
- Only plain-text conversations are cached; images and tool calls bypass the cache
- TTL with background purge, and an admin endpoint to invalidate a tenant's cache
- Streaming requests can be served from the cache

### Token budgets and rate limits
- Daily and monthly token budgets per tenant, plus an optional daily budget per API key
- **Atomic reserve-then-settle** using Redis Lua scripts to prevent race conditions
- Requests-per-minute limits with a sliding-window algorithm
- `429` responses with `Retry-After` and `X-Aegis-Tokens-Remaining` headers

### Provider failover
- Pluggable provider adapters behind one interface: OpenAI (and any OpenAI-compatible API such as Gemini, Ollama, or vLLM), Azure OpenAI, and an in-process mock
- Per-provider model lists and model aliases (e.g. `gpt-4o-mini` → `gemini-2.0-flash`, or an Azure deployment name)
- **Resilience4j** circuit breakers, timeouts, and retries with exponential backoff and jitter
- Priority-based routing that skips providers whose circuit is open; streams fail over only before the first chunk

### Security
- PII redaction for emails, credit cards (Luhn-checked), US SSNs, Aadhaar numbers, IP addresses, and phone numbers, with placeholders restored in responses, including streamed ones
- Weighted rule-based prompt-injection detection, configurable per tenant (block, flag, or off)
- API keys stored only as SHA-256 hashes, with per-key model allow-lists
- Audit log of blocked and flagged requests and redactions

### Observability
- OpenTelemetry tracing across the pipeline (budget, cache lookup, provider call), exported over OTLP
- Prometheus metrics: latency (p50/p95/p99), tokens, cost, cost saved by the cache, security events, circuit breaker state
- Usage ledger in PostgreSQL, queryable through the admin API

### Admin API
Protected by `AEGIS_ADMIN_TOKEN`. All endpoints are under `/admin`.

| Method and path | Purpose |
| --- | --- |
| `GET/POST /tenants`, `GET/PUT /tenants/{id}` | List, create, inspect (keys, budget usage, cache size), and update tenants |
| `GET/POST /tenants/{id}/keys`, `DELETE /keys/{keyId}` | List, create (the secret is shown once), and revoke API keys |
| `DELETE /tenants/{id}/cache` | Invalidate a tenant's semantic cache |
| `GET /usage/summary?hours=24&tenantId=` | Totals, time series, and breakdowns by model, provider, and tenant |
| `GET /usage/requests`, `GET /audit` | Recent requests and security events |
| `GET /providers`, `GET /config` | Provider and circuit breaker health, and effective defaults |

---

## Tech stack

| Layer | Technology |
| --- | --- |
| Language / framework | Java 21, Spring Boot 3.4, Spring WebFlux |
| Caching and limits | Redis (Lua scripts) |
| Vector search | PostgreSQL + pgvector |
| Persistence | PostgreSQL, Flyway migrations |
| Resilience | Resilience4j |
| Observability | Micrometer, OpenTelemetry, Prometheus, Grafana, Jaeger |
| Testing | JUnit 5, Testcontainers, MockWebServer, k6 (load tests, planned) |
| Packaging | Docker Compose, GitHub Actions |

---

## Getting started

### Prerequisites
- Java 21
- Docker and Docker Compose
- An API key for at least one provider, unless you use the demo profile

### Run locally

```bash
git clone https://github.com/Vinoth-M-08/aegis.git
cd aegis

docker compose up -d          # PostgreSQL + pgvector, Redis, Prometheus, Grafana, Jaeger
cp .env.example .env          # add OPENAI_API_KEY and AEGIS_ADMIN_TOKEN
./mvnw spring-boot:run
```

The gateway starts on `http://localhost:8080`.

### Try it without any API keys

The `demo` profile uses two mock providers (the first fails 20% of the time, so failover is visible), local hashing embeddings for the cache, a fixed admin token (`demo-admin-token`), and seeds a demo tenant with the key `aegis_tenant_demo_key`:

```bash
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
```

### Use it with the OpenAI SDK

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="aegis_tenant_demo_key",
)

response = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "Summarize the CAP theorem in two lines."}],
)
print(response.choices[0].message.content)
```

Streaming (`stream=True`) works the same way.

### Or with curl

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer aegis_tenant_demo_key" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": "Hello"}]
      }'
```

Response headers include gateway metadata:

```
X-Aegis-Request-Id: 9f2c...
X-Aegis-Provider: openai            # or "cache"
X-Aegis-Cache: HIT | MISS | BYPASS
X-Aegis-Tokens-Remaining: 48210     # when the tenant has a limit
X-Aegis-Redactions: 2               # when PII was redacted
X-Aegis-Guard: flagged              # when the guard is in flag mode and fired
X-Aegis-Failover: true              # when a lower-priority provider answered
```

### Create a tenant and key

```bash
curl -X POST http://localhost:8080/admin/tenants \
  -H "Authorization: Bearer $AEGIS_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "team-search", "dailyTokenLimit": 200000, "requestsPerMinute": 120}'

curl -X POST http://localhost:8080/admin/tenants/<tenant-id>/keys \
  -H "Authorization: Bearer $AEGIS_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "search-backend", "allowedModels": ["gpt-4o-mini"]}'
```

---

## Configuration

Abridged from [application.yml](src/main/resources/application.yml):

```yaml
aegis:
  providers:
    - name: openai
      type: openai            # openai | azure-openai | mock
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      priority: 1
      timeout: 20s
    - name: gemini
      type: openai            # Gemini's OpenAI-compatible endpoint
      base-url: https://generativelanguage.googleapis.com/v1beta/openai
      api-key: ${GEMINI_API_KEY}
      priority: 2
      model-aliases: { gpt-4o-mini: gemini-2.0-flash }
  resilience:
    max-attempts: 2
    failure-rate-threshold: 50
    open-state-duration: 30s
  budgets:
    default-daily-tokens: 100000
    default-requests-per-minute: 60
  cache:
    enabled: true
    similarity-threshold: 0.92
    ttl: 24h
    embedding:
      type: openai            # openai | hashing (local, for demos)
      model: text-embedding-3-small
  security:
    pii-redaction: true
    restore-pii: true
    injection-guard: block    # block | flag | off
  pricing:                    # USD per 1M tokens, used for the cost ledger
    gpt-4o-mini: { input-per-1m: 0.15, output-per-1m: 0.60 }
```

---

## Project structure

```
aegis/
├── src/main/java/com/aegis/
│   ├── api/            # OpenAI-compatible controller, DTOs, error handling, request ids
│   ├── gateway/        # The request pipeline that ties the stages together
│   ├── auth/           # API keys, tenants and their policies
│   ├── budget/         # Token budgets, rate limiting, token estimation
│   ├── guard/          # Prompt-injection detection
│   ├── redaction/      # PII detectors, placeholder mapping, streaming restore
│   ├── cache/          # Embeddings and the pgvector semantic cache
│   ├── provider/       # Provider adapters, router, circuit breakers
│   ├── usage/          # Usage ledger, pricing, audit log, admin queries
│   ├── admin/          # Admin API and its auth
│   └── config/         # Configuration properties, wiring, demo seeding
├── src/main/resources/
│   ├── lua/            # Redis Lua scripts
│   └── db/migration/   # Flyway migrations
├── infra/              # Prometheus and Grafana provisioning
├── docker-compose.yml
└── README.md
```

---

## Design decisions

**Why Redis Lua for budgets instead of check-then-decrement?**
Two concurrent requests can both read "enough budget" and both spend it. A Lua script runs atomically in Redis, so the check and the reservation happen as one step.

**Why reserve-then-settle?**
The real token count is only known after the provider responds. Aegis reserves an estimate up front and settles to the actual count afterwards, so budgets stay accurate without blocking on provider latency.

**Why pgvector instead of a separate vector database?**
The cache needs transactional consistency with tenant metadata and the usage ledger. Keeping embeddings in PostgreSQL avoids running and syncing a second datastore.

**Why scope the cache by tenant and system prompt?**
Two tenants can send the same user prompt with different system instructions and expect different answers. Scoping prevents wrong answers and cross-tenant data leaks.

**Why redact before caching?**
Cached entries must never contain raw PII. Redaction runs first, so both the provider call and the cache only see placeholders. Placeholders are numbered in order of appearance, so a cached answer containing `<EMAIL_1>` is restored with the *current* request's first email.

**Why guard before reserving budget?**
Blocked requests never reach a provider, so they should not hold budget even briefly.

---

## Testing

```bash
./mvnw verify
```

Unit tests cover redaction, the injection guard, streaming restore, pricing, cache scoping, the HTTP provider adapters, and router failover and circuit breakers. Integration tests run the full gateway and the Lua scripts against real PostgreSQL + pgvector and Redis using Testcontainers; they are skipped when Docker is not running.

---

## Benchmarks

Benchmarks will be published here once the k6 load-test suite is complete.

| Metric | Result |
| --- | --- |
| Gateway overhead (cache miss, p99) | TBD |
| Cache-hit latency (p99) | TBD |
| Cache hit rate on repeated workload | TBD |
| Sustained throughput | TBD |
| Cost reduction from caching | TBD |

---

## Roadmap

### Done
- [x] OpenAI-compatible `/v1/chat/completions` endpoint
- [x] Streaming responses (SSE), including PII restore and cache hits
- [x] API-key auth and tenant resolution
- [x] Redis Lua token budgets and rate limiting
- [x] Provider adapters (OpenAI and OpenAI-compatible APIs such as Gemini, Azure OpenAI, mock)
- [x] Circuit breakers and failover routing
- [x] Semantic cache with pgvector
- [x] PII redaction
- [x] Prompt-injection detection (rule-based)
- [x] Usage and cost ledger
- [x] OpenTelemetry tracing and Prometheus metrics
- [x] Admin API for tenants, keys, budgets, usage, and audit
- [x] Docker Compose for local infrastructure

### Left to do
- [ ] **Admin dashboard UI**: web app for tenants, keys, budgets, usage charts, recent requests, and the audit log, served at `/dashboard` (the route is in place; the app is not built yet)
- [ ] **Grafana dashboard**: Prometheus and Grafana are provisioned, but the dashboard JSON (`infra/grafana/dashboards/`) is not written yet
- [ ] **Dockerfile**: needed by `docker compose --profile app`, which does not work until it exists
- [ ] **k6 load tests and published benchmarks** (`loadtest/`)
- [ ] **Run the integration tests in CI with Docker** and fix anything they surface; they are written but have not been run yet
- [ ] **Classifier-based injection detection** alongside the rules
- [ ] **Store streamed responses in the cache** (streams are served from the cache but not written to it)

---

## License

MIT
