CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenants (
    id                         UUID PRIMARY KEY,
    name                       TEXT        NOT NULL UNIQUE,
    active                     BOOLEAN     NOT NULL DEFAULT TRUE,
    -- NULL means "use the gateway default"
    daily_token_limit          BIGINT,
    monthly_token_limit        BIGINT,
    requests_per_minute        INTEGER,
    -- Empty means every model is allowed
    allowed_models             TEXT[]      NOT NULL DEFAULT '{}',
    injection_mode             TEXT        NOT NULL DEFAULT 'BLOCK',
    pii_redaction              BOOLEAN     NOT NULL DEFAULT TRUE,
    cache_enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    cache_similarity_threshold DOUBLE PRECISION,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name              TEXT        NOT NULL,
    key_prefix        TEXT        NOT NULL,
    key_hash          TEXT        NOT NULL UNIQUE,
    allowed_models    TEXT[]      NOT NULL DEFAULT '{}',
    daily_token_limit BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    last_used_at      TIMESTAMPTZ
);

CREATE INDEX api_keys_tenant ON api_keys (tenant_id);

CREATE TABLE usage_records (
    id                BIGSERIAL PRIMARY KEY,
    request_id        TEXT          NOT NULL,
    tenant_id         UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    api_key_id        UUID,
    model             TEXT,
    provider          TEXT,
    outcome           TEXT          NOT NULL,
    cache_status      TEXT          NOT NULL,
    streamed          BOOLEAN       NOT NULL DEFAULT FALSE,
    prompt_tokens     INTEGER       NOT NULL DEFAULT 0,
    completion_tokens INTEGER       NOT NULL DEFAULT 0,
    total_tokens      INTEGER       NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(14, 6) NOT NULL DEFAULT 0,
    saved_usd         NUMERIC(14, 6) NOT NULL DEFAULT 0,
    latency_ms        INTEGER       NOT NULL,
    error_code        TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX usage_records_created ON usage_records (created_at DESC);
CREATE INDEX usage_records_tenant_created ON usage_records (tenant_id, created_at DESC);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    request_id TEXT        NOT NULL,
    tenant_id  UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    api_key_id UUID,
    event_type TEXT        NOT NULL,
    details    JSONB       NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_created ON audit_log (created_at DESC);

CREATE TABLE semantic_cache (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    model        TEXT         NOT NULL,
    -- Hash of system prompt and generation parameters; must match exactly
    scope_hash   TEXT         NOT NULL,
    -- Redacted, normalized prompt kept for inspection
    prompt_text  TEXT         NOT NULL,
    embedding    vector(1536) NOT NULL,
    response     JSONB        NOT NULL,
    hit_count    INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX semantic_cache_scope ON semantic_cache (tenant_id, model, scope_hash);
CREATE INDEX semantic_cache_expiry ON semantic_cache (expires_at);
CREATE INDEX semantic_cache_embedding ON semantic_cache USING hnsw (embedding vector_cosine_ops);
