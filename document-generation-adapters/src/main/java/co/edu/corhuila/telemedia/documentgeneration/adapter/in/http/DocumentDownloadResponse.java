package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;

/**
 * Public representation {@code DocumentDownload} of document-service.yaml: the presigned
 * URL and its expiry as RFC 3339 UTC text. The object key is never exposed (ADR-015).
 */
public record DocumentDownloadResponse(String downloadUrl, String expiresAt) {

    public static DocumentDownloadResponse from(DownloadLink link) {
        return new DocumentDownloadResponse(link.url().toString(), link.expiresAt().toString());
    }
}
