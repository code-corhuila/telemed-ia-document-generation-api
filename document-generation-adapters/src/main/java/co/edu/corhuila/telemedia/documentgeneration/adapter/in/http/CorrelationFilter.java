package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Numeral 5.3.9: reuses the received {@code X-Correlation-Id} or generates one, returns it
 * in the response, writes it on every log line ({@code correlationId}) and exposes it as
 * the {@code traceId} of the error envelope. It also writes one access line per request.
 *
 * <p>The header is tracing metadata only; it is never used for authentication. A value
 * that is too long or carries characters outside {@code [A-Za-z0-9._:-]} is replaced, so
 * it cannot inject content into the logs.
 *
 * <p>It runs just inside the server observation filter (order {@code HIGHEST_PRECEDENCE + 1}),
 * so the request span is already open and the access line carries its trace (OBS-DG-02).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    static final String ATTRIBUTE = CorrelationFilter.class.getName() + ".id";

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Logger ACCESS = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            ACCESS.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }

    /** The correlation id of the current request; a fresh one if the filter did not run. */
    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : resolve(null);
    }

    private static String resolve(String candidate) {
        return candidate != null && SAFE_VALUE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
