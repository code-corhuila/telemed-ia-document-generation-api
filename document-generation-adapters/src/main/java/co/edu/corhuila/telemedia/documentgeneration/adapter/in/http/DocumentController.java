package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.ApiError.FieldDetail;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Read routes of document-service.yaml. The local route is {@code /api/documents};
 * the gateway exposes it as {@code /api/v1/documents} (D-C1). Input is validated here
 * (400); the caller comes only from the validated token, never from a parameter.
 */
@RestController
@RequestMapping(path = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentController {

    private final DocumentQueries queries;

    public DocumentController(DocumentQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @GetMapping({"", "/"})
    public DocumentPageResponse listMine(HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "limit", defaultValue = "" + PageRequest.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "status", required = false) String status) {
        List<FieldDetail> invalid = new ArrayList<>();
        if (page < 1) {
            invalid.add(new FieldDetail("page", "must be 1 or greater"));
        }
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            invalid.add(new FieldDetail("limit", "must be between 1 and " + PageRequest.MAX_LIMIT));
        }
        Optional<DocumentStatus> statusFilter = parseStatus(status, invalid);
        if (!invalid.isEmpty()) {
            throw new InvalidRequestException(invalid);
        }
        return DocumentPageResponse.from(queries.listMine(caller(request), statusFilter, new PageRequest(page, limit)));
    }

    @GetMapping("/{id}")
    public DocumentResponse getById(HttpServletRequest request, @PathVariable("id") UUID id) {
        return DocumentResponse.from(queries.getById(caller(request), id));
    }

    private static Caller caller(HttpServletRequest request) {
        AuthenticatedUser user = AuthFilter.currentUser(request);
        return new Caller(user.subject(), user.roles());
    }

    private static Optional<DocumentStatus> parseStatus(String status, List<FieldDetail> invalid) {
        if (status == null) {
            return Optional.empty();
        }
        for (DocumentStatus candidate : DocumentStatus.values()) {
            if (candidate.name().equals(status)) {
                return Optional.of(candidate);
            }
        }
        invalid.add(new FieldDetail("status", "must be one of PENDING, GENERATING, AVAILABLE, ERROR"));
        return Optional.empty();
    }
}
