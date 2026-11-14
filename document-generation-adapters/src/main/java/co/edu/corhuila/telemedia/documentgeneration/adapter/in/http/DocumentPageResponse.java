package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.List;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/** {@code PaginatedDocumentResponse}: {@code {data, meta}} (D-C5). */
public record DocumentPageResponse(List<DocumentResponse> data, Meta meta) {

    public record Meta(int page, int limit, long total, long totalPages) {
    }

    public static DocumentPageResponse from(Page<ConsultationDocument> page) {
        return new DocumentPageResponse(
                page.items().stream().map(DocumentResponse::from).toList(),
                new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
    }
}
