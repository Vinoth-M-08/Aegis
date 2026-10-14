package com.aegis.api;

public final class AegisHeaders {

    public static final String REQUEST_ID = "X-Aegis-Request-Id";
    public static final String PROVIDER = "X-Aegis-Provider";
    public static final String CACHE = "X-Aegis-Cache";
    public static final String CACHE_SIMILARITY = "X-Aegis-Cache-Similarity";
    public static final String TOKENS_REMAINING = "X-Aegis-Tokens-Remaining";
    public static final String GUARD = "X-Aegis-Guard";
    public static final String REDACTIONS = "X-Aegis-Redactions";
    public static final String FAILOVER = "X-Aegis-Failover";

    private AegisHeaders() {
    }
}
