package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.Rs256Verifier.InvalidTokenException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every route except {@code /health} requires {@code Authorization: Bearer <token>},
 * validated here by the service itself (numeral 5.3.7). Any failure is
 * {@code 401 UNAUTHORIZED} with the shared envelope; the reason goes to the log only.
 * Runs after {@link CorrelationFilter}, so the rejection carries the trace.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = AuthFilter.class.getName() + ".user";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/health");
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    private final Rs256Verifier verifier;
    private final ObjectMapper objectMapper;

    public AuthFilter(Rs256Verifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    /** The user authenticated for this request; only reachable behind this filter. */
    public static AuthenticatedUser currentUser(HttpServletRequest request) {
        Object user = request.getAttribute(ATTRIBUTE);
        if (user instanceof AuthenticatedUser authenticated) {
            return authenticated;
        }
        throw new IllegalStateException("no authenticated user on this request");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            reject(request, response, "missing bearer token");
            return;
        }
        try {
            AuthenticatedUser user = verifier.verify(authorization.substring(BEARER_PREFIX.length()).trim());
            request.setAttribute(ATTRIBUTE, user);
        } catch (InvalidTokenException e) {
            reject(request, response, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        LOG.warn("rejected {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.forStatus(HttpServletResponse.SC_UNAUTHORIZED, CorrelationFilter.current(request)));
    }
}
