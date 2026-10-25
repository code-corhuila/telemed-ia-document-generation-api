package co.edu.corhuila.telemedia.documentgeneration.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary.Medication;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * The PDF is read back with PDFBox's own text extraction, so the tests check what a
 * reader of the file sees rather than how it was drawn.
 */
class PdfBoxRendererTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-23T14:05:00Z");
    private static final UUID SUMMARY = UUID.fromString("e0000000-0000-0000-0000-000000000102");
    private static final UUID PATIENT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID CONSULTATION = UUID.fromString("00000000-0000-0000-0000-000000009001");

    private final PdfBoxRenderer renderer = new PdfBoxRenderer(BOGOTA);
    private final ConsultationDocument document = ConsultationDocument
            .request(UUID.fromString("f0000000-0000-0000-0000-000000000001"), SUMMARY, PATIENT, ISSUED_AT)
            .startGeneration();

    private static ConsultationSummary summary(String recommendations, List<Medication> medications,
            String diagnosis, String observations, String referral, Map<String, String> followUp) {
        return new ConsultationSummary(SUMMARY, CONSULTATION, UUID.randomUUID(), PATIENT, diagnosis, recommendations,
                medications,
                observations, referral, followUp);
    }

    private static ConsultationSummary complete() {
        return summary("Reposo relativo y abundante hidratación.",
                List.of(new Medication("Acetaminofén", "500 mg", "cada 8 horas", "5 días"),
                        new Medication("Loratadina", null, null, null)),
                "Rinofaringitis aguda", "Paciente estable, sin signos de alarma.", "Otorrinolaringología",
                Map.of("control", "en 15 días", "exámenes", "hemograma"));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument loaded = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(loaded);
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument loaded = Loader.loadPDF(pdf)) {
            return loaded.getNumberOfPages();
        }
    }

    @Test
    void theResultIsAPdfWithItsTitleInTheMetadata() throws IOException {
        byte[] pdf = renderer.render(document, complete(), ISSUED_AT);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (PDDocument loaded = Loader.loadPDF(pdf)) {
            assertThat(loaded.getDocumentInformation().getTitle()).isEqualTo("Resumen de atención médica");
            assertThat(loaded.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(ISSUED_AT);
        }
    }

    @Test
    void theHeaderHasTheIdentifiersAndTheLocalIssueTime() throws IOException {
        String text = text(renderer.render(document, complete(), ISSUED_AT));

        assertThat(text)
                .contains("Resumen de atención médica")
                .contains("Documento: " + document.id())
                .contains("Resumen: " + SUMMARY)
                .contains("Consulta: " + CONSULTATION)
                .contains("Fecha de emisión: 2026-10-23 09:05 (UTC-05:00)");
    }

    @Test
    void everyClinicalSectionIsPrinted() throws IOException {
        String text = text(renderer.render(document, complete(), ISSUED_AT));

        assertThat(text)
                .contains("Diagnóstico", "Rinofaringitis aguda")
                .contains("Recomendaciones", "Reposo relativo y abundante hidratación.")
                .contains("Medicamentos")
                .contains("Acetaminofén — dosis: 500 mg · frecuencia: cada 8 horas · duración: 5 días")
                .contains("Loratadina")
                .contains("Observaciones", "Paciente estable, sin signos de alarma.")
                .contains("Remisión", "Otorrinolaringología")
                .contains("Seguimiento", "control: en 15 días", "exámenes: hemograma");
        assertThat(text.indexOf("Diagnóstico")).isLessThan(text.indexOf("Recomendaciones"));
        assertThat(text.indexOf("control:")).isLessThan(text.indexOf("exámenes:"));
    }

    @Test
    void absentOptionalSectionsAreLeftOut() throws IOException {
        String text = text(renderer.render(document,
                summary("Control en una semana.", List.of(), null, null, null, Map.of()), ISSUED_AT));

        assertThat(text).contains("Recomendaciones", "Control en una semana.")
                .doesNotContain("Diagnóstico", "Medicamentos", "Observaciones", "Remisión", "Seguimiento");
    }

    @Test
    void thePatientIdentifierIsNotPrinted() throws IOException {
        assertThat(text(renderer.render(document, complete(), ISSUED_AT))).doesNotContain(PATIENT.toString());
    }

    @Test
    void longTextWrapsAndContinuesOnNewPagesNumbered() throws IOException {
        String paragraph = "Tomar el medicamento con alimentos y registrar cualquier reacción adversa. ";
        String recommendations = paragraph.repeat(400) + "FIN";

        byte[] pdf = renderer.render(document, summary(recommendations, List.of(), null, null, null, null),
                ISSUED_AT);

        int pages = pages(pdf);
        assertThat(pages).isGreaterThan(1);
        String text = text(pdf);
        assertThat(text).contains("FIN").contains("Página 1 de " + pages).contains("Página " + pages + " de " + pages);
    }

    @Test
    void aWordWiderThanTheLineIsBrokenInsteadOfOverflowing() throws IOException {
        String word = "x".repeat(400);

        String text = text(renderer.render(document,
                summary(word, List.of(), null, null, null, null), ISSUED_AT));

        assertThat(text.replaceAll("\\s", "")).contains(word);
    }

    @Test
    void lineBreaksOfTheSummaryAreKept() throws IOException {
        String text = text(renderer.render(document,
                summary("Primera línea\r\nSegunda línea\n\tTercera", List.of(), null, null, null, null), ISSUED_AT));

        assertThat(text).containsSubsequence("Primera línea", "\n", "Segunda línea", "\n", "Tercera");
    }

    @Test
    void charactersTheFontCannotDrawAreReplacedAndAccentsKept() throws IOException {
        String text = text(renderer.render(document,
                summary("Ñandú ¿cómo? ¡ánimo! 😀 → 漢", List.of(), null, null, null, null), ISSUED_AT));

        assertThat(text).contains("Ñandú ¿cómo? ¡ánimo! ? ? ?");
    }
}
