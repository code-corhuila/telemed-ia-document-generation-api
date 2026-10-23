package co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import io.minio.MinioClient;

/**
 * Presigning is computed locally with the credentials (no request to the server when the
 * region is configured), so the URL can be checked without a running server.
 */
class S3DocumentStorageTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00.750Z");
    private static final String KEY = "patients/ana/consultations/s-1/doc-1.pdf";
    private static final String SECRET = "test-secret-key-0001";

    /** Internal endpoint, as the service reaches the server inside the platform network. */
    private final MinioClient client = client("http://object-storage:9000");
    /** Public endpoint, as the browser reaches the server: only used to sign. */
    private final MinioClient signer = client("http://localhost:9000");
    private final S3DocumentStorage storage = new S3DocumentStorage(client, signer, "telemed-documents",
            Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    private static MinioClient client(String endpoint) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials("test-access-key", SECRET)
                .region("us-east-1")
                .build();
    }

    @Test
    void theUrlIsSignedForThePublicEndpointNotTheInternalOne() {
        assertThat(storage.presignDownload(KEY).url().getHost()).isEqualTo("localhost").isNotEqualTo("object-storage");
    }

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
    void anUnreachableServerWhileEnsuringTheBucketIsStorageUnavailable() {
        S3DocumentStorage unreachable = new S3DocumentStorage(client("http://127.0.0.1:1"), signer,
                "telemed-documents", Duration.ofMinutes(5), Clock.systemUTC());

        assertThatThrownBy(unreachable::ensureBucket).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void anUnreachableServerWhileStoringIsStorageUnavailable() {
        S3DocumentStorage unreachable = new S3DocumentStorage(client("http://127.0.0.1:1"), signer,
                "telemed-documents", Duration.ofMinutes(5), Clock.systemUTC());

        assertThatThrownBy(() -> unreachable.store(KEY, new byte[] {'%', 'P', 'D', 'F'}))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void theTimeToLiveMustBeBetweenOneSecondAndSevenDays() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new S3DocumentStorage(client, signer, "telemed-documents", Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3DocumentStorage(client, signer, "telemed-documents", Duration.ofDays(8), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3DocumentStorage(client, signer, " ", Duration.ofMinutes(5), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
