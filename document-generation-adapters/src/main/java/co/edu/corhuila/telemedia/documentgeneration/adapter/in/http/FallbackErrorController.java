package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Replaces Spring Boot's default {@code /error} page, so errors raised outside the
 * controllers (the container or a filter) also answer with the shared envelope.
 */
@RestController
public class FallbackErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        String traceId = CorrelationFilter.current(request);
        return ResponseEntity.status(status)
                .header(CorrelationFilter.HEADER, traceId)
                .body(ApiError.forStatus(status, traceId));
    }
}
