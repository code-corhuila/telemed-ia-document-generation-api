package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.Objects;
import java.util.Set;

/**
 * Identity taken from a validated token: {@code sub} and {@code roles} (ADR-007).
 * Never from a header such as {@code X-User-Id} (D-C12).
 */
public record AuthenticatedUser(String subject, Set<String> roles) {

    public static final String PATIENT = "PATIENT";
    public static final String PROFESSIONAL = "PROFESSIONAL";
    public static final String ADMIN = "ADMIN";

    public AuthenticatedUser {
        Objects.requireNonNull(subject, "subject");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
