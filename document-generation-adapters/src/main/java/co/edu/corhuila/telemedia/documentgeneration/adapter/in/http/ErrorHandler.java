package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.ApiError.FieldDetail;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.InvalidStatusTransitionException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The single place where errors become HTTP responses (Anexo C). The domain throws typed
 * errors and does not know what a 422 is. Internal detail goes to the log only.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldDetail> details = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.add(new FieldDetail(error.getField(), error.getDefaultMessage()));
        }
        return respond(400, ApiError.invalid(details, trace(request)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidValue(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return invalidField(ex.getName(), "has an invalid format", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return invalidField(ex.getHeaderName(), "header required", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        return invalidField(ex.getParameterName(), "is required", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(400, ApiError.of(ApiError.VALIDATION_ERROR,
                "the request body is not valid JSON", trace(request)));
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return respond(400, ApiError.invalid(ex.details(), trace(request)));
    }

    /** DEC-004: the same 403 for a non-patient and for another patient's document. */
    @ExceptionHandler(DocumentAccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(DocumentAccessDeniedException ex, HttpServletRequest request) {
        return respond(403, ApiError.forStatus(403, trace(request)));
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ApiError> notFound(DocumentNotFoundException ex, HttpServletRequest request) {
        return respond(404, ApiError.forStatus(404, trace(request)));
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ResponseEntity<ApiError> invalidTransition(InvalidStatusTransitionException ex, HttpServletRequest request) {
        return respond(422, ApiError.of(ApiError.INVALID_STATUS_TRANSITION, ex.getMessage(), trace(request)));
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ResponseEntity<ApiError> ruleViolation(BusinessRuleViolationException ex, HttpServletRequest request) {
        return respond(422, ApiError.of(ApiError.BUSINESS_RULE_VIOLATION, ex.getMessage(), trace(request)));
    }

    /** Framework errors that already carry a status: unknown route, method, media type. */
    @ExceptionHandler({ServletException.class, ErrorResponseException.class})
    ResponseEntity<ApiError> framework(Exception ex, HttpServletRequest request) {
        if (!(ex instanceof ErrorResponse response) || response.getStatusCode().is5xxServerError()) {
            return unexpected(ex, request);
        }
        int status = response.getStatusCode().value();
        return ResponseEntity.status(status)
                .headers(response.getHeaders())
                .body(ApiError.forStatus(status, trace(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        LOG.error("unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(500, ApiError.forStatus(500, trace(request)));
    }

    private ResponseEntity<ApiError> invalidField(String field, String message, HttpServletRequest request) {
        return respond(400, ApiError.invalid(List.of(new FieldDetail(field, message)), trace(request)));
    }

    private static ResponseEntity<ApiError> respond(int status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }

    private static String trace(HttpServletRequest request) {
        return CorrelationFilter.current(request);
    }
}
