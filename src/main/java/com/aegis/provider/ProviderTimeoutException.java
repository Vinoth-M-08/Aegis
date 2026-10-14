package com.aegis.provider;

import java.time.Duration;

public class ProviderTimeoutException extends ProviderException {

    public ProviderTimeoutException(String provider, Duration timeout, Throwable cause) {
        super(provider, "Provider '" + provider + "' did not respond within " + timeout.toMillis() + "ms", cause);
    }
}
