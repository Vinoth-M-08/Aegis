package com.aegis.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI-style error body, so client SDKs surface gateway errors the same way as provider errors.
 */
public record ErrorResponse(Error error) {

    public static ErrorResponse of(String message, String type, String code) {
        return new ErrorResponse(new Error(message, type, code));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String message, String type, String code) {
    }
}
