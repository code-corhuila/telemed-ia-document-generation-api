package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.List;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.ApiError.FieldDetail;

/** Input rejected at the HTTP boundary: {@code 400 VALIDATION_ERROR} naming every field. */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldDetail> details;

    public InvalidRequestException(List<FieldDetail> details) {
        super("the request has invalid fields");
        this.details = List.copyOf(details);
    }

    public List<FieldDetail> details() {
        return details;
    }
}
