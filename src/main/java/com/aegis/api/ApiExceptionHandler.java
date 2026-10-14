package com.aegis.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import com.aegis.api.dto.ErrorResponse;
import com.aegis.provider.ProviderException;
import com.aegis.provider.ProviderTimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String INVALID_REQUEST = "invalid_request_error";
    private static final String PROVIDER_ERROR = "provider_error";

    private final ObjectMapper objectMapper;

    public ApiExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex) {
        FieldError fieldError = ex.getFieldError();
        String message = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : "Request validation failed";
        return error(HttpStatus.BAD_REQUEST, message, INVALID_REQUEST, null);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(ServerWebInputException ex) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON", INVALID_REQUEST, null);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException ex) {
        return error(HttpStatus.CONFLICT, "A record with the same unique value already exists", INVALID_REQUEST, "conflict");
    }

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus()).contentType(MediaType.APPLICATION_JSON);
        ex.getHeaders().forEach(builder::header);
        return builder.body(ErrorResponse.of(ex.getMessage(), ex.getType(), ex.getCode()));
    }

    @ExceptionHandler(ProviderTimeoutException.class)
    ResponseEntity<ErrorResponse> handleProviderTimeout(ProviderTimeoutException ex) {
        log.warn(ex.getMessage());
        return error(HttpStatus.GATEWAY_TIMEOUT, ex.getMessage(), PROVIDER_ERROR, "provider_timeout");
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ErrorResponse> handleProvider(ProviderException ex) {
        Integer status = ex.getUpstreamStatus();
        // Errors caused by the client's request (unknown model, bad parameters) are passed through
        // so the caller can fix them. Anything else is the gateway's or provider's problem, and the
        // upstream body is only logged because it may describe our credentials.
        if (status != null && !ex.warrantsFailover() && status >= 400 && status < 500) {
            String message = upstreamMessage(ex.getUpstreamBody());
            return error(HttpStatus.valueOf(status), message != null ? message : ex.getMessage(), INVALID_REQUEST, null);
        }
        log.warn("{} body={}", ex.getMessage(), ex.getUpstreamBody());
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage(), PROVIDER_ERROR, null);
    }

    private String upstreamMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode message = objectMapper.readTree(body).path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message, String type, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(message, type, code));
    }
}
