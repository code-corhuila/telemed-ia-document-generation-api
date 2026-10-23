package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** The PDF could not be produced (DEC-005, "PDF error"). */
public class PdfRenderingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PdfRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
