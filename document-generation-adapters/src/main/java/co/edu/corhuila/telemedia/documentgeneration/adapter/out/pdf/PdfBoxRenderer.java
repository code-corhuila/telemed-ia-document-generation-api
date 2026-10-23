package co.edu.corhuila.telemedia.documentgeneration.adapter.out.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary.Medication;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderingException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * The consultation summary as an A4 PDF with Apache PDFBox (DEC-006): the fields of
 * {@code AttentionSummary}, the issue date and the document, summary and consultation
 * identifiers. No personal data is printed, not even the patient identifier. The labels
 * are in Spanish, the language of the portals the patient uses.
 *
 * <p>The standard Helvetica font needs no font file and covers Spanish (WinAnsi); a
 * character it cannot draw is printed as {@code ?} instead of failing the document.
 */
public final class PdfBoxRenderer implements PdfRenderer {

    static final String TITLE = "Resumen de atención médica";

    private static final DateTimeFormatter ISSUED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm '(UTC'xxx')'");
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 56;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float BOTTOM = MARGIN + 20;
    private static final float LEADING = 1.4f;
    private static final float TITLE_SIZE = 16;
    private static final float HEADING_SIZE = 12;
    private static final float BODY_SIZE = 10.5f;
    private static final float META_SIZE = 9;

    private final ZoneId zone;

    /** @param zone the time zone of the issue date printed on the document */
    public PdfBoxRenderer(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public byte[] render(ConsultationDocument document, ConsultationSummary summary, Instant issuedAt) {
        try (PDDocument pdf = new PDDocument()) {
            pdf.getDocumentInformation().setTitle(TITLE);
            pdf.getDocumentInformation().setCreationDate(GregorianCalendar.from(issuedAt.atZone(zone)));
            try (Writer writer = new Writer(pdf)) {
                writer.paragraph(TITLE, writer.bold, TITLE_SIZE);
                writer.gap(6);
                writer.paragraph("Documento: " + document.id(), writer.regular, META_SIZE);
                writer.paragraph("Resumen: " + summary.summaryId(), writer.regular, META_SIZE);
                writer.paragraph("Consulta: " + summary.consultationId(), writer.regular, META_SIZE);
                writer.paragraph("Fecha de emisión: " + ISSUED.format(issuedAt.atZone(zone)), writer.regular,
                        META_SIZE);
                writer.rule();
                writer.section("Diagnóstico", summary.diagnosis());
                writer.section("Recomendaciones", summary.recommendations());
                writer.section("Medicamentos", medications(summary.medications()));
                writer.section("Observaciones", summary.observations());
                writer.section("Remisión", summary.referral());
                writer.section("Seguimiento", followUp(summary.followUp()));
            }
            numberPages(pdf);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new PdfRenderingException("the PDF could not be rendered", e);
        }
    }

    private static String medications(List<Medication> medications) {
        List<String> lines = new ArrayList<>();
        for (Medication medication : medications) {
            List<String> details = new ArrayList<>();
            detail(details, "dosis", medication.dosage());
            detail(details, "frecuencia", medication.frequency());
            detail(details, "duración", medication.duration());
            lines.add("• " + medication.name() + (details.isEmpty() ? "" : " — " + String.join(" · ", details)));
        }
        return String.join("\n", lines);
    }

    private static void detail(List<String> details, String label, String value) {
        if (value != null && !value.isBlank()) {
            details.add(label + ": " + value);
        }
    }

    private static String followUp(Map<String, String> followUp) {
        List<String> lines = new ArrayList<>();
        new TreeMap<>(followUp).forEach((key, value) -> lines.add(key + ": " + value));
        return String.join("\n", lines);
    }

    private static void numberPages(PDDocument pdf) throws IOException {
        PDFont font = new PDType1Font(FontName.HELVETICA);
        int total = pdf.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            String label = "Página " + (i + 1) + " de " + total;
            try (PDPageContentStream stream = new PDPageContentStream(pdf, pdf.getPage(i), AppendMode.APPEND, true,
                    true)) {
                stream.beginText();
                stream.setFont(font, META_SIZE);
                stream.newLineAtOffset(MARGIN + (WIDTH - width(font, META_SIZE, label)) / 2, MARGIN);
                stream.showText(label);
                stream.endText();
            }
        }
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /** Writes lines top to bottom, opening a new page when the current one is full. */
    private static final class Writer implements AutoCloseable {

        private final PDDocument pdf;
        private final PDFont regular = new PDType1Font(FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(FontName.HELVETICA_BOLD);
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument pdf) throws IOException {
            this.pdf = pdf;
            newPage();
        }

        /** A heading and its text; nothing when the text is absent or blank. */
        void section(String heading, String text) throws IOException {
            if (text == null || text.isBlank()) {
                return;
            }
            gap(10);
            // Keep the heading with the first line of its text.
            if (y - HEADING_SIZE * LEADING - BODY_SIZE * LEADING < BOTTOM) {
                newPage();
            }
            paragraph(heading, bold, HEADING_SIZE);
            gap(2);
            paragraph(text, regular, BODY_SIZE);
        }

        void paragraph(String text, PDFont font, float size) throws IOException {
            for (String line : wrap(drawable(text, font), font, size)) {
                float height = size * LEADING;
                if (y - height < BOTTOM) {
                    newPage();
                }
                y -= height;
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(line);
                stream.endText();
            }
        }

        void gap(float height) {
            y -= height;
        }

        void rule() throws IOException {
            gap(8);
            stream.setLineWidth(0.5f);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        /** Lines of the text that fit the width; a word wider than a line is broken. */
        private static List<String> wrap(String text, PDFont font, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : text.split("\\R", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" +")) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (width(font, size, candidate) <= WIDTH) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    while (width(font, size, word) > WIDTH) {
                        int cut = fitting(word, font, size);
                        lines.add(word.substring(0, cut));
                        word = word.substring(cut);
                    }
                    line.append(word);
                }
                lines.add(line.toString());
            }
            return lines;
        }

        private static int fitting(String word, PDFont font, float size) throws IOException {
            int cut = 1;
            while (cut < word.length() && width(font, size, word.substring(0, cut + 1)) <= WIDTH) {
                cut++;
            }
            return cut;
        }

        /** Tabs and other controls become spaces; characters the font lacks become '?'. */
        private static String drawable(String text, PDFont font) {
            StringBuilder out = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r') {
                    out.appendCodePoint(codePoint);
                } else if (Character.isISOControl(codePoint)) {
                    out.append(' ');
                } else if (encodable(font, Character.toString(codePoint))) {
                    out.appendCodePoint(codePoint);
                } else {
                    out.append('?');
                }
            });
            return out.toString();
        }

        private static boolean encodable(PDFont font, String character) {
            try {
                font.encode(character);
                return true;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PAGE);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = PAGE.getHeight() - MARGIN;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
