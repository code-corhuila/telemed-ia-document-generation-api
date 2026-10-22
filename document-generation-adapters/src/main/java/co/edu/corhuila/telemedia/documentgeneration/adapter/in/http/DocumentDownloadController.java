package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;

import jakarta.servlet.http.HttpServletRequest;

/**
 * GET /{id}/download of document-service.yaml. Every issued URL is logged with the
 * request's correlation id so download attempts can be traced (ADR-015); the URL itself
 * is never logged.
 */
@RestController
@RequestMapping(path = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentDownloadController {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentDownloadController.class);

    private final DocumentDownloads downloads;

    public DocumentDownloadController(DocumentDownloads downloads) {
        this.downloads = Objects.requireNonNull(downloads, "downloads");
    }

    @GetMapping("/{id}/download")
    public DocumentDownloadResponse download(HttpServletRequest request, @PathVariable("id") UUID id) {
        AuthenticatedUser user = AuthFilter.currentUser(request);
        DocumentDownloadResponse response = DocumentDownloadResponse.from(
                downloads.requestDownload(new Caller(user.subject(), user.roles()), id));
        LOG.info("download URL issued for document {} until {}", id, response.expiresAt());
        return response;
    }
}
