package com.aegis.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request rejected by the gateway itself, rendered as an OpenAI-style error.
 */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;
    private final Map<String, String> headers;

    public GatewayException(HttpStatus status, String type, String code, String message) {
        this(status, type, code, message, Map.of());
    }

    public GatewayException(HttpStatus status, String type, String code, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.headers = Map.copyOf(headers);
    }

    public static GatewayException invalidRequest(String code, String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request_error", code, message);
    }

    public static GatewayException unauthorized(String message) {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "authentication_error", "invalid_api_key", message);
    }

    public static GatewayException forbidden(String code, String message) {
        return new GatewayException(HttpStatus.FORBIDDEN, "permission_error", code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
