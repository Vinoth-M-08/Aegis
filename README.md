# Aegis: LLM Gateway & AI Traffic Control

> An OpenAI-compatible gateway that sits between your applications and LLM providers, adding semantic caching, per-tenant token budgets, provider failover, PII redaction, and prompt-injection detection, with full cost and latency tracing.

![Status](https://img.shields.io/badge/status-in%20progress-orange)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
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
| Users trying to override system instructions | **Prompt-injection detection** with rule-based and classifier checks |
| No visibility into who spends what | **Per-request tracing** of tokens, cost, latency, cache hits, and provider used |

Applications keep using the standard OpenAI SDK. They only change the base URL.

---

## Architecture

```mermaid
flowchart LR
    C[Client apps<br/>OpenAI SDK] -->|/v1/chat/completions| G[Aegis Gateway<br/>Spring Boot]

    subgraph Pipeline [Request pipeline]
        direction TB
        A1[1. Auth and tenant resolution<br/>API key to tenant]
        A2[2. Budget check<br/>Redis Lua, atomic]
        A3[3. Prompt-injection guard]
        A4[4. PII redaction]
        A5[5. Semantic cache lookup<br/>pgvector]
        A6[6. Provider router<br/>failover and circuit breaker]
        A1 --> A2 --> A3 --> A4 --> A5 --> A6
    end

    G --> A1
    A5 -- cache hit --> G
    A6 --> P1[OpenAI]
    A6 --> P2[Azure OpenAI]
    A6 --> P3[Gemini / local model]

    A2 <--> R[(Redis<br/>budgets, rate limits)]
    A5 <--> V[(PostgreSQL + pgvector<br/>prompt embeddings, responses)]
    G --> T[(PostgreSQL<br/>usage and cost ledger)]
    G --> O[OpenTelemetry<br/>Prometheus / Grafana]
```

### Request lifecycle

1. **Authenticate**: the API key is resolved to a tenant and its policy (allowed models, budget, redaction rules).
2. **Reserve budget**: a Redis Lua script checks and reserves the estimated token count in one atomic step, so concurrent requests cannot overspend.
3. **Guard**: the prompt is checked for injection patterns. Depending on policy, the request is blocked, flagged, or allowed.
4. **Redact**: PII is replaced with typed placeholders (`<EMAIL_1>`, `<PHONE_1>`) and restored in the response where safe.
5. **Cache lookup**: the prompt is embedded and searched in pgvector. Above the similarity threshold, the cached response is returned without calling a provider.
6. **Route**: on a cache miss, the router picks the provider by priority and health. Failures trip a circuit breaker and the request fails over to the next provider.
7. **Settle and record**: actual token usage replaces the reservation, and cost, latency, cache status, and provider are written to the usage ledger and emitted as traces.

---

## Features

### Semantic caching
- Embeds normalized prompts and stores them with responses in **pgvector**
- Cosine-similarity lookup with a configurable threshold per tenant
- Cache keys scoped by tenant, model, and system prompt, so tenants never see each other's data
- TTL and manual invalidation endpoints

### Token budgets and rate limits
- Daily and monthly token budgets per tenant and per API key
- **Atomic reserve-then-settle** using Redis Lua scripts to prevent race conditions
- Requests-per-minute limits with a sliding-window algorithm
- Clear `429` responses with remaining budget headers

### Provider failover
- Pluggable provider adapters behind one interface (Strategy + Adapter patterns)
- **Resilience4j** circuit breakers, timeouts, and retries with exponential backoff and jitter
- Priority-based and health-based routing

### Security
- PII redaction with configurable detectors
- Prompt-injection detection
- Hashed API keys, per-key model allow-lists
- Audit log of blocked and flagged requests

### Observability
- OpenTelemetry traces across the full pipeline
- Prometheus metrics: latency (p50/p95/p99), cache hit rate, tokens, cost, provider errors
- Grafana dashboard included

---

## Tech stack

| Layer | Technology |
| --- | --- |
| Language / framework | Java 21, Spring Boot 3, Spring WebFlux |
| Caching and limits | Redis (Lua scripts) |
| Vector search | PostgreSQL + pgvector |
| Persistence | PostgreSQL, Flyway migrations |
| Resilience | Resilience4j |
| Observability | OpenTelemetry, Prometheus, Grafana |
| Testing | JUnit 5, Testcontainers, k6 (load tests) |
| Packaging | Docker, Docker Compose, GitHub Actions |

---

## Getting started

### Prerequisites
- Java 21
- Docker and Docker Compose
- An API key for at least one provider (OpenAI, Azure OpenAI, or Gemini)

### Run locally

```bash
git clone https://github.com/Vinoth-M-08/aegis.git
cd aegis

cp .env.example .env          # add your provider keys
./mvnw spring-boot:run
```

> Docker Compose (Redis, PostgreSQL + pgvector, Prometheus, Grafana) arrives with the budget and cache work. API-key auth is not enforced yet, so any bearer token is accepted for now.

The gateway starts on `http://localhost:8080`.

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
X-Aegis-Cache: HIT | MISS
X-Aegis-Provider: openai
X-Aegis-Tokens-Remaining: 48210
X-Aegis-Request-Id: 9f2c...
```

Currently emitted: `X-Aegis-Provider` and `X-Aegis-Request-Id`. The others arrive with the cache and budget features.

---

## Configuration

```yaml
aegis:
  cache:
    enabled: true
    similarity-threshold: 0.92
    ttl: 24h
  budgets:
    default-daily-tokens: 100000
  providers:
    - name: openai
      priority: 1
      timeout: 20s
    - name: azure-openai
      priority: 2
      timeout: 20s
  security:
    pii-redaction: true
    injection-guard: block   # block | flag | off
```

---

## Project structure

```
aegis/
├── src/main/java/com/aegis/
│   ├── api/            # OpenAI-compatible controllers
│   ├── auth/           # API keys and tenant resolution
│   ├── budget/         # Redis Lua budget and rate limiting
│   ├── cache/          # Embeddings and pgvector semantic cache
│   ├── guard/          # Prompt-injection detection
│   ├── redaction/      # PII detectors and placeholder mapping
│   ├── provider/       # Provider adapters, router, circuit breakers
│   ├── usage/          # Cost and usage ledger
│   └── observability/  # Tracing and metrics
├── src/main/resources/
│   ├── lua/            # Redis Lua scripts
│   └── db/migration/   # Flyway migrations
├── loadtest/           # k6 scripts
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
Cached entries must never contain raw PII. Redaction runs first, so both the provider call and the cache only see placeholders.

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

- [x] OpenAI-compatible `/v1/chat/completions` endpoint (non-streaming)
- [ ] API-key auth and tenant resolution
- [ ] Redis Lua token budgets and rate limiting
- [ ] Provider adapters (OpenAI ✅, Azure OpenAI, Gemini)
- [ ] Circuit breakers and failover routing
- [ ] Semantic cache with pgvector
- [ ] PII redaction
- [ ] Prompt-injection detection
- [ ] Usage and cost ledger
- [ ] OpenTelemetry tracing and Grafana dashboard
- [ ] Streaming responses (SSE)
- [ ] k6 load tests and published benchmarks
- [ ] Admin API and dashboard for tenants and budgets

---

## License

MIT
