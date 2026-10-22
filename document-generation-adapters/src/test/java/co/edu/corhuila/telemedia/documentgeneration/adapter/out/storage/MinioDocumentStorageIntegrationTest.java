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
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

/**
 * The presigned URL against a real MinIO (Anexo C, "Integración"): the object is downloaded
 * with a plain HTTP GET and no credentials, as the browser does. Skipped, not failed, when
 * TEST_MINIO_ENDPOINT is not set. Each test removes the object it uploads.
 */
@EnabledIfEnvironmentVariable(named = "TEST_MINIO_ENDPOINT", matches = ".+")
class MinioDocumentStorageIntegrationTest {

    private static final String BUCKET = "telemed-documents";
    private static final byte[] PDF = "%PDF-1.7 integration test".getBytes(StandardCharsets.US_ASCII);

    private final HttpClient http = HttpClient.newHttpClient();
    private MinioClient client;
    private String key;

    @BeforeEach
    void upload() throws Exception {
        client = MinioClient.builder()
                .endpoint(System.getenv("TEST_MINIO_ENDPOINT"))
                .credentials(System.getenv("TEST_MINIO_ACCESS_KEY"), System.getenv("TEST_MINIO_SECRET_KEY"))
                .region("us-east-1")
                .build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
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
    void thePresignedUrlDownloadsTheObjectWithoutCredentials() throws Exception {
        MinioDocumentStorage storage = new MinioDocumentStorage(client, BUCKET, Duration.ofMinutes(5),
                Clock.systemUTC());

        HttpResponse<byte[]> response = get(storage.presignDownload(key));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);
    }

    @Test
    void anExpiredUrlIsRejectedByStorage() throws Exception {
        MinioDocumentStorage storage = new MinioDocumentStorage(client, BUCKET, Duration.ofSeconds(1),
                Clock.systemUTC());
        DownloadLink link = storage.presignDownload(key);

        Thread.sleep(2_500);

        assertThat(get(link).statusCode()).isEqualTo(403);
    }
}
