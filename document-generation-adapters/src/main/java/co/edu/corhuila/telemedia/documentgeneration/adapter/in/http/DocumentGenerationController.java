package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.ApiError.FieldDetail;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * POST /generate of document-service.yaml. The header and the body
 * ({@code GenerateDocumentRequest}, {@code additionalProperties: false}) are validated
 * here and every invalid field is named in one 400. The body is read as a tree so the
 * contract does not depend on how the JSON mapper treats unknown properties.
 */
@RestController
@RequestMapping(path = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentGenerationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String PUBLIC_PATH = "/api/v1/documents/";

    private static final String SUMMARY_ID = "summaryId";
    private static final String PATIENT_ID = "patientId";
    private static final Set<String> FIELDS = Set.of(SUMMARY_ID, PATIENT_ID);

    private final DocumentGeneration generation;

    public DocumentGenerationController(DocumentGeneration generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentResponse> generate(HttpServletRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        List<FieldDetail> invalid = new ArrayList<>();
        validateKey(idempotencyKey, invalid);
        UUID summaryId = null;
        UUID patientId = null;
        if (body == null || !body.isObject()) {
            invalid.add(new FieldDetail("body", "must be a JSON object"));
        } else {
            rejectUnknownFields(body, invalid);
            summaryId = uuid(body, SUMMARY_ID, invalid);
            patientId = uuid(body, PATIENT_ID, invalid);
        }
        if (!invalid.isEmpty()) {
            throw new InvalidRequestException(invalid);
        }

        AuthenticatedUser user = AuthFilter.currentUser(request);
        GenerationResult result = generation.request(new Caller(user.subject(), user.roles()),
                new GenerateDocumentCommand(idempotencyKey, summaryId, patientId));
        DocumentResponse response = DocumentResponse.from(result.document());
        if (result.created()) {
            return ResponseEntity.created(URI.create(PUBLIC_PATH + response.id())).body(response);
        }
        return ResponseEntity.ok(response);
    }

    private static void validateKey(String key, List<FieldDetail> invalid) {
        if (key == null) {
            invalid.add(new FieldDetail(IDEMPOTENCY_KEY, "header required"));
        } else if (key.length() < GenerateDocumentCommand.MIN_KEY_LENGTH
                || key.length() > GenerateDocumentCommand.MAX_KEY_LENGTH) {
            invalid.add(new FieldDetail(IDEMPOTENCY_KEY, "must have " + GenerateDocumentCommand.MIN_KEY_LENGTH
                    + " to " + GenerateDocumentCommand.MAX_KEY_LENGTH + " characters"));
        }
    }

    private static void rejectUnknownFields(JsonNode body, List<FieldDetail> invalid) {
        for (Iterator<String> names = body.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                invalid.add(new FieldDetail(name, "is not allowed"));
            }
        }
    }

    /** A canonical UUID string; {@code UUID.fromString} alone also accepts forms like {@code 1-1-1-1-1}. */
    private static UUID uuid(JsonNode body, String field, List<FieldDetail> invalid) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            invalid.add(new FieldDetail(field, "is required"));
            return null;
        }
        if (value.isTextual()) {
            try {
                UUID id = UUID.fromString(value.asText());
                if (id.toString().equalsIgnoreCase(value.asText())) {
                    return id;
                }
            } catch (IllegalArgumentException e) {
                // reported below
            }
        }
        invalid.add(new FieldDetail(field, "must be a UUID"));
        return null;
    }
}
