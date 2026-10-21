package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is asking, as established by the HTTP adapter from a validated token
 * ({@code sub} and {@code roles}). The core never reads identity from anything else.
 */
public record Caller(String subject, Set<String> roles) {

    public static final String PATIENT = "PATIENT";
    public static final String ADMIN = "ADMIN";

    public Caller {
        Objects.requireNonNull(subject, "subject");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** The patient identifier, only when the caller is a patient whose subject is a UUID. */
    public Optional<UUID> patientId() {
        if (!hasRole(PATIENT)) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(subject);
            return id.toString().equalsIgnoreCase(subject) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
