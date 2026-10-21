package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Shared error envelope (D-C6, Anexo C): {@code {error, message, details?, traceId}}.
 * {@code details} is omitted, never {@code null}, when no field-level detail applies.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<FieldDetail> details, String traceId) {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    /** One invalid field, path variable, query parameter or header. */
    public record FieldDetail(String field, String message) {
    }

    public ApiError {
        details = details == null || details.isEmpty() ? null : List.copyOf(details);
    }

    public static ApiError of(String error, String message, String traceId) {
        return new ApiError(error, message, null, traceId);
    }

    public static ApiError invalid(List<FieldDetail> details, String traceId) {
        return new ApiError(VALIDATION_ERROR, "the request has invalid fields", details, traceId);
    }

    /** Envelope for an error known only by its HTTP status, with a neutral message. */
    public static ApiError forStatus(int status, String traceId) {
        return switch (status) {
            case 400 -> of(VALIDATION_ERROR, "the request is not valid", traceId);
            case 401 -> of(UNAUTHORIZED, "authentication is required", traceId);
            case 403 -> of(FORBIDDEN, "the operation is not allowed for this user", traceId);
            case 404 -> of(NOT_FOUND, "the requested resource does not exist", traceId);
            case 405 -> of("METHOD_NOT_ALLOWED", "the HTTP method is not allowed on this route", traceId);
            case 406 -> of("NOT_ACCEPTABLE", "the requested representation is not available", traceId);
            case 415 -> of("UNSUPPORTED_MEDIA_TYPE", "the request content type is not supported", traceId);
            case 422 -> of(BUSINESS_RULE_VIOLATION, "the request violates a business rule", traceId);
            case 503 -> of(SERVICE_UNAVAILABLE, "the service is temporarily unavailable", traceId);
            default -> status >= 400 && status < 500
                    ? of(VALIDATION_ERROR, "the request is not valid", traceId)
                    : of(INTERNAL_ERROR, "an unexpected error occurred", traceId);
        };
    }
}
