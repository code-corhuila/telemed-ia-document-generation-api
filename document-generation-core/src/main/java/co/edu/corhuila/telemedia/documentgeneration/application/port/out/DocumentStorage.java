package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;

/** Object storage of the generated PDFs (ADR-015). The key is never exposed to clients. */
public interface DocumentStorage {

    /**
     * A presigned GET URL for the object, valid for the configured time.
     *
     * @throws StorageUnavailableException if the URL cannot be issued
     */
    DownloadLink presignDownload(String objectKey);

    /**
     * Stores the PDF under the key, replacing it if present (ADR-015: the key derives from
     * the document, so a retry overwrites instead of duplicating).
     *
     * @throws StorageUnavailableException if the object cannot be stored
     */
    void store(String objectKey, byte[] pdf);
}
