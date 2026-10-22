package co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import io.minio.MinioClient;

/**
 * Presigning is computed locally with the credentials (no request to MinIO when the
 * region is configured), so the URL can be checked without a running server.
 */
class MinioDocumentStorageTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.750Z");
    private static final String KEY = "patients/ana/consultations/s-1/doc-1.pdf";
    private static final String SECRET = "test-secret-key-0001";

    private final MinioClient client = MinioClient.builder()
            .endpoint("http://localhost:9000")
            .credentials("test-access-key", SECRET)
            .region("us-east-1")
            .build();
    private final MinioDocumentStorage storage = new MinioDocumentStorage(client, "telemed-documents",
            Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theUrlPointsToTheObjectAndIsSignedForFiveMinutes() {
        DownloadLink link = storage.presignDownload(KEY);

        assertThat(link.url().getScheme()).isEqualTo("http");
        assertThat(link.url().getHost()).isEqualTo("localhost");
        assertThat(link.url().getPort()).isEqualTo(9000);
        assertThat(link.url().getPath()).isEqualTo("/telemed-documents/" + KEY);
        assertThat(link.url().getQuery())
                .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
                .contains("X-Amz-Credential=test-access-key/")
                .contains("/us-east-1/s3/aws4_request")
                .contains("X-Amz-Expires=300")
                .contains("X-Amz-Signature=");
    }

    @Test
    void expiresAtIsTheIssueTimePlusTheTimeToLiveInWholeSeconds() {
        assertThat(storage.presignDownload(KEY).expiresAt()).isEqualTo(Instant.parse("2026-10-22T15:05:00Z"));
    }

    @Test
    void theSecretKeyNeverAppearsInTheUrl() {
        assertThat(storage.presignDownload(KEY).url().toString()).doesNotContain(SECRET);
    }

    @Test
    void theTimeToLiveMustBeBetweenOneSecondAndSevenDays() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MinioDocumentStorage(client, "telemed-documents", Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MinioDocumentStorage(client, "telemed-documents", Duration.ofDays(8), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MinioDocumentStorage(client, " ", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
