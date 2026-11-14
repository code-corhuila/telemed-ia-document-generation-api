package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** A short-lived presigned URL to download a PDF directly from storage (ADR-015). */
public record DownloadLink(URI url, Instant expiresAt) {

    public DownloadLink {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
