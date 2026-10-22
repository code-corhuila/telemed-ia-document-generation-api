package co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;

/**
 * The presigned URL against a real S3-compatible server (Anexo C, "Integración"): the object
 * is downloaded with a plain HTTP GET and no credentials, as the browser does, through the
 * public endpoint (TEST_STORAGE_PUBLIC_ENDPOINT, the internal one when not set). Skipped,
 * not failed, when TEST_STORAGE_ENDPOINT is not set. Each test removes what it creates.
 */
@EnabledIfEnvironmentVariable(named = "TEST_STORAGE_ENDPOINT", matches = ".+")
class S3DocumentStorageIntegrationTest {

    private static final String BUCKET = "telemed-documents";
    private static final byte[] PDF = "%PDF-1.7 integration test".getBytes(StandardCharsets.US_ASCII);

    private final HttpClient http = HttpClient.newHttpClient();
    private final MinioClient client = client(System.getenv("TEST_STORAGE_ENDPOINT"));
    private final MinioClient signer = client(publicEndpoint());
    private String key;

    private static String publicEndpoint() {
        String endpoint = System.getenv("TEST_STORAGE_PUBLIC_ENDPOINT");
        return endpoint == null || endpoint.isBlank() ? System.getenv("TEST_STORAGE_ENDPOINT") : endpoint;
    }

    private static MinioClient client(String endpoint) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(System.getenv("TEST_STORAGE_ACCESS_KEY"), System.getenv("TEST_STORAGE_SECRET_KEY"))
                .region("us-east-1")
                .build();
    }

    private S3DocumentStorage storage(String bucket, Duration timeToLive) {
        return new S3DocumentStorage(client, signer, bucket, timeToLive, Clock.systemUTC());
    }

    @BeforeEach
    void upload() throws Exception {
        storage(BUCKET, Duration.ofMinutes(5)).ensureBucket();
        key = "integration-tests/" + UUID.randomUUID() + ".pdf";
        client.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                .stream(new ByteArrayInputStream(PDF), PDF.length, -1)
                .contentType("application/pdf")
                .build());
    }

    @AfterEach
    void remove() throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
    }

    private HttpResponse<byte[]> get(DownloadLink link) throws Exception {
        return http.send(HttpRequest.newBuilder(link.url()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void thePresignedUrlDownloadsTheObjectThroughThePublicEndpointWithoutCredentials() throws Exception {
        DownloadLink link = storage(BUCKET, Duration.ofMinutes(5)).presignDownload(key);

        HttpResponse<byte[]> response = get(link);

        assertThat(link.url().toString()).startsWith(publicEndpoint());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);
    }

    @Test
    void anExpiredUrlIsRejectedByStorage() throws Exception {
        DownloadLink link = storage(BUCKET, Duration.ofSeconds(1)).presignDownload(key);

        Thread.sleep(2_500);

        assertThat(get(link).statusCode()).isEqualTo(403);
    }

    @Test
    void ensuringTheBucketCreatesItOnceAndCanBeRepeated() throws Exception {
        String bucket = "it-" + UUID.randomUUID().toString().substring(0, 8);
        S3DocumentStorage storage = storage(bucket, Duration.ofMinutes(5));
        try {
            storage.ensureBucket();
            storage.ensureBucket();

            assertThat(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())).isTrue();
        } finally {
            client.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
        }
    }
}
