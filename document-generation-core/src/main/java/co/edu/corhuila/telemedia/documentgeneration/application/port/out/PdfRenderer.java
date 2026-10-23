package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.time.Instant;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/** Turns a summary into the PDF of its document (DEC-006). */
public interface PdfRenderer {

    /**
     * @throws PdfRenderingException if the PDF cannot be produced
     */
    byte[] render(ConsultationDocument document, ConsultationSummary summary, Instant issuedAt);
}
