package co.edu.corhuila.telemedia.documentgeneration.adapter.out.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
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
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetails;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderingException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * The patient's consultation summary as an A4 PDF with Apache PDFBox (DEC-006, DEC-015): a
 * header band, who attended whom and when, the reason for the visit (the only text written
 * with AI, agent DEC-019), the professional's record exactly as recorded, with the
 * medications as a table and the referral in a highlighted box, and a footer on every page.
 * No identifier other than the document number is printed. Labels are in Spanish.
 *
 * <p>The standard Helvetica font needs no font file and covers Spanish (WinAnsi); a
 * character it cannot draw is printed as {@code ?} instead of failing the document.
 */
public final class PdfBoxRenderer implements PdfRenderer {

    static final String TITLE = "Resumen de tu consulta médica";
    static final String BRAND = "TeleMed IA";
    static final String MISSING = "—";
    static final String AI_NOTE = "Solo «Motivo de tu consulta» fue redactado con IA, a partir de lo que contaste "
            + "en la preconsulta.";
    static final String RECORD_NOTE = "Lo demás es el registro de tu profesional de salud, sin cambios.";

    private static final DateTimeFormatter CONSULTED = DateTimeFormatter
            .ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-CO"));
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 56;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float BOTTOM = MARGIN + 30;
    private static final float BAND_HEIGHT = 70;
    private static final float LEADING = 1.4f;
    private static final float TITLE_SIZE = 18;
    private static final float HEADING_SIZE = 12;
    private static final float BODY_SIZE = 10.5f;
    private static final float TABLE_SIZE = 9.5f;
    private static final float META_SIZE = 8;
    private static final float CELL_PADDING = 4;
    private static final float BOX_PADDING = 10;

    private static final Color BRAND_COLOR = new Color(15, 118, 110);
    private static final Color TEXT_COLOR = new Color(31, 41, 55);
    private static final Color MUTED_COLOR = new Color(107, 114, 128);
    private static final Color TABLE_HEADER_FILL = new Color(230, 242, 241);
    private static final Color LINE_COLOR = new Color(209, 213, 219);
    private static final Color REFERRAL_FILL = new Color(255, 247, 230);
    private static final Color REFERRAL_BORDER = new Color(245, 158, 11);

    private static final String[] MEDICATION_HEADERS = {"Medicamento", "Dosis", "Frecuencia", "Duración",
            "Indicaciones"};
    private static final float[] MEDICATION_WIDTHS = {0.24f, 0.14f, 0.18f, 0.14f, 0.30f};

    private final ZoneId zone;

    /** @param zone the time zone of the consultation date printed on the document */
    public PdfBoxRenderer(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public byte[] render(ConsultationDocument document, ConsultationSummary summary, PatientDetails details,
            Instant issuedAt) {
        PatientDetails shown = details == null ? PatientDetails.NONE : details;
        try (PDDocument pdf = new PDDocument()) {
            pdf.getDocumentInformation().setTitle(TITLE);
            pdf.getDocumentInformation().setCreationDate(GregorianCalendar.from(issuedAt.atZone(zone)));
            try (Writer writer = new Writer(pdf)) {
                writer.band();
                writer.labeled("Paciente: ", orMissing(shown.patientName()));
                writer.labeled("Profesional: ", orMissing(shown.professionalName()));
                writer.labeled("Fecha de la consulta: ", summary.completedAt() == null ? MISSING
                        : CONSULTED.format(summary.completedAt().atZone(zone)));
                writer.labeled("Documento N.º ", document.id().toString());
                writer.rule();
                writer.section("Motivo de tu consulta", shown.reasonForVisit());
                writer.section("Diagnóstico", summary.diagnosis());
                writer.medications(summary.medications());
                writer.section("Recomendaciones", summary.recommendations());
                writer.section("Observaciones", summary.observations());
                writer.section("Seguimiento", followUp(summary.followUp()));
                writer.referral(summary.referral());
            }
            footer(pdf, present(shown.reasonForVisit()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new PdfRenderingException("the PDF could not be rendered", e);
        }
    }

    private static String orMissing(String value) {
        return present(value) ? value.strip() : MISSING;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String followUp(Map<String, String> followUp) {
        List<String> lines = new ArrayList<>();
        new TreeMap<>(followUp).forEach((key, value) -> lines.add(key + ": " + value));
        return String.join("\n", lines);
    }

    /** Page numbers on every page and, when the reason is there, which text was written with AI. */
    private static void footer(PDDocument pdf, boolean withAiNote) throws IOException {
        PDFont font = new PDType1Font(FontName.HELVETICA);
        int total = pdf.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            String label = "Página " + (i + 1) + " de " + total;
            try (PDPageContentStream stream = new PDPageContentStream(pdf, pdf.getPage(i), AppendMode.APPEND, true,
                    true)) {
                stream.setNonStrokingColor(MUTED_COLOR);
                if (withAiNote) {
                    text(stream, font, META_SIZE, MARGIN, MARGIN + 10, AI_NOTE);
                    text(stream, font, META_SIZE, MARGIN, MARGIN, RECORD_NOTE);
                }
                text(stream, font, META_SIZE, MARGIN + (WIDTH - width(font, META_SIZE, label)) / 2, MARGIN - 18,
                        label);
            }
        }
    }

    private static void text(PDPageContentStream stream, PDFont font, float size, float x, float y, String text)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(drawable(text, font));
        stream.endText();
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /** Lines of the text that fit {@code maxWidth}; a word wider than a line is broken. */
    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : drawable(text, font).split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" +")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                while (width(font, size, word) > maxWidth) {
                    int cut = fitting(word, font, size, maxWidth);
                    lines.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static int fitting(String word, PDFont font, float size, float maxWidth) throws IOException {
        int cut = 1;
        while (cut < word.length() && width(font, size, word.substring(0, cut + 1)) <= maxWidth) {
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

    /** Writes top to bottom, opening a new page when the current one is full. */
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

        /** The brand band across the top of the first page. */
        void band() throws IOException {
            float top = PAGE.getHeight();
            stream.setNonStrokingColor(BRAND_COLOR);
            stream.addRect(0, top - BAND_HEIGHT, PAGE.getWidth(), BAND_HEIGHT);
            stream.fill();
            stream.setNonStrokingColor(Color.WHITE);
            text(stream, bold, 10, MARGIN, top - 26, BRAND);
            text(stream, bold, TITLE_SIZE, MARGIN, top - 52, TITLE);
            stream.setNonStrokingColor(TEXT_COLOR);
            y = top - BAND_HEIGHT - 22;
        }

        /** A bold label and its value on one line. */
        void labeled(String label, String value) throws IOException {
            float height = BODY_SIZE * LEADING;
            if (y - height < BOTTOM) {
                newPage();
            }
            y -= height;
            float labelWidth = width(bold, BODY_SIZE, label);
            stream.setNonStrokingColor(MUTED_COLOR);
            text(stream, bold, BODY_SIZE, MARGIN, y, label);
            stream.setNonStrokingColor(TEXT_COLOR);
            List<String> lines = wrap(value, regular, BODY_SIZE, WIDTH - labelWidth);
            text(stream, regular, BODY_SIZE, MARGIN + labelWidth, y, lines.get(0));
            for (String line : lines.subList(1, lines.size())) {
                y -= height;
                text(stream, regular, BODY_SIZE, MARGIN + labelWidth, y, line);
            }
        }

        /** A heading and its text; nothing when the text is absent or blank. */
        void section(String heading, String text) throws IOException {
            if (!present(text)) {
                return;
            }
            heading(heading, BODY_SIZE * LEADING);
            paragraph(text, regular, BODY_SIZE);
        }

        /** Medicamento, dosis, frecuencia, duración and indicaciones, one row each. */
        void medications(List<Medication> medications) throws IOException {
            if (medications.isEmpty()) {
                return;
            }
            float[] widths = new float[MEDICATION_WIDTHS.length];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = WIDTH * MEDICATION_WIDTHS[i];
            }
            List<List<String>> header = cells(MEDICATION_HEADERS, bold, widths);
            heading("Medicamentos", rowHeight(header) * 2);
            y -= 4;
            row(header, bold, widths, true);
            for (Medication medication : medications) {
                List<List<String>> cells = cells(new String[] {medication.name(), medication.dosage(),
                        medication.frequency(), medication.duration(), medication.instructions()}, regular, widths);
                if (y - rowHeight(cells) < BOTTOM) {
                    newPage();
                    row(header, bold, widths, true);
                }
                row(cells, regular, widths, false);
            }
        }

        /** The referral in a highlighted box, kept on one page when it fits. */
        void referral(String referral) throws IOException {
            if (!present(referral)) {
                return;
            }
            List<String> lines = wrap(referral, regular, BODY_SIZE, WIDTH - 2 * BOX_PADDING);
            float height = 2 * BOX_PADDING + HEADING_SIZE * LEADING + lines.size() * BODY_SIZE * LEADING;
            float pageRoom = PAGE.getHeight() - MARGIN - BOTTOM;
            if (height > pageRoom) {
                section("Remisión", referral);
                return;
            }
            y -= 14;
            if (y - height < BOTTOM) {
                newPage();
            }
            stream.setNonStrokingColor(REFERRAL_FILL);
            stream.setStrokingColor(REFERRAL_BORDER);
            stream.setLineWidth(1);
            stream.addRect(MARGIN, y - height, WIDTH, height);
            stream.fillAndStroke();
            float x = MARGIN + BOX_PADDING;
            y -= BOX_PADDING + HEADING_SIZE;
            stream.setNonStrokingColor(BRAND_COLOR);
            text(stream, bold, HEADING_SIZE, x, y, "Remisión");
            stream.setNonStrokingColor(TEXT_COLOR);
            y -= HEADING_SIZE * (LEADING - 1);
            for (String line : lines) {
                y -= BODY_SIZE * LEADING;
                text(stream, regular, BODY_SIZE, x, y, line);
            }
            y -= BOX_PADDING;
        }

        void rule() throws IOException {
            y -= 10;
            stream.setStrokingColor(LINE_COLOR);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        /** A section heading, kept with at least {@code keepWith} of what follows it. */
        private void heading(String heading, float keepWith) throws IOException {
            y -= 12;
            if (y - HEADING_SIZE * LEADING - keepWith < BOTTOM) {
                newPage();
            }
            stream.setNonStrokingColor(BRAND_COLOR);
            paragraph(heading, bold, HEADING_SIZE);
            stream.setNonStrokingColor(TEXT_COLOR);
            y -= 2;
        }

        private void paragraph(String text, PDFont font, float size) throws IOException {
            for (String line : wrap(text, font, size, WIDTH)) {
                float height = size * LEADING;
                if (y - height < BOTTOM) {
                    newPage();
                }
                y -= height;
                text(stream, font, size, MARGIN, y, line);
            }
        }

        private static List<List<String>> cells(String[] values, PDFont font, float[] widths) throws IOException {
            List<List<String>> cells = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                String value = present(values[i]) ? values[i].strip() : MISSING;
                cells.add(wrap(value, font, TABLE_SIZE, widths[i] - 2 * CELL_PADDING));
            }
            return cells;
        }

        private static float rowHeight(List<List<String>> cells) {
            int lines = cells.stream().mapToInt(List::size).max().orElse(1);
            return lines * TABLE_SIZE * LEADING + 2 * CELL_PADDING;
        }

        private void row(List<List<String>> cells, PDFont font, float[] widths, boolean header) throws IOException {
            float height = rowHeight(cells);
            if (header) {
                stream.setNonStrokingColor(TABLE_HEADER_FILL);
                stream.addRect(MARGIN, y - height, WIDTH, height);
                stream.fill();
            }
            float x = MARGIN;
            stream.setNonStrokingColor(TEXT_COLOR);
            for (int i = 0; i < cells.size(); i++) {
                float lineY = y - CELL_PADDING;
                for (String line : cells.get(i)) {
                    lineY -= TABLE_SIZE * LEADING;
                    text(stream, font, TABLE_SIZE, x + CELL_PADDING, lineY + TABLE_SIZE * (LEADING - 1), line);
                }
                x += widths[i];
            }
            y -= height;
            stream.setStrokingColor(LINE_COLOR);
            stream.setLineWidth(0.5f);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PAGE);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            stream.setNonStrokingColor(TEXT_COLOR);
            y = PAGE.getHeight() - MARGIN;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
