package com.aegis.provider;

public class ProviderException extends RuntimeException {

    private final String provider;
    private final Integer upstreamStatus;
    private final String upstreamBody;

    /** The provider answered with an error status. */
    public ProviderException(String provider, int upstreamStatus, String upstreamBody) {
        super("Provider '" + provider + "' returned HTTP " + upstreamStatus);
        this.provider = provider;
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    /** The provider could not be reached or did not answer. */
    public ProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.upstreamStatus = null;
        this.upstreamBody = null;
    }

    public String getProvider() {
        return provider;
    }

    /** HTTP status from the provider, or {@code null} if no response was received. */
    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamBody() {
        return upstreamBody;
    }

    /** Whether retrying the same provider might succeed: network errors, timeouts, rate limits, 5xx. */
    public boolean isTransient() {
        return upstreamStatus == null || upstreamStatus == 408 || upstreamStatus == 409
                || upstreamStatus == 429 || upstreamStatus >= 500;
    }

    /**
     * Whether a different provider might succeed. Adds auth failures (a misconfigured provider)
     * to the transient errors. Errors caused by the request itself, such as an unknown model or
     * bad parameters, would fail everywhere and are returned to the client instead.
     */
    public boolean warrantsFailover() {
        return isTransient() || upstreamStatus == 401 || upstreamStatus == 403;
    }
}
