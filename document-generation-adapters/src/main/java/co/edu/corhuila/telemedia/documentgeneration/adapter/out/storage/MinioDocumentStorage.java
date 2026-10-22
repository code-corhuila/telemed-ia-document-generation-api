package co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.http.Method;

/**
 * PDFs live in one MinIO bucket and are downloaded by the browser through a presigned GET
 * URL (ADR-015). With the region configured on the client, signing is computed locally.
 *
 * <p>{@code expiresAt} is taken before signing and truncated to the second, so it is never
 * later than the real expiry of the URL.
 */
public final class MinioDocumentStorage implements DocumentStorage {

    /** S3 limit for presigned URLs. */
    static final Duration MAX_TIME_TO_LIVE = Duration.ofDays(7);

    private final MinioClient client;
    private final String bucket;
    private final Duration timeToLive;
    private final Clock clock;

    public MinioDocumentStorage(MinioClient client, String bucket, Duration timeToLive, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("the bucket name is empty");
        }
        if (timeToLive.compareTo(Duration.ofSeconds(1)) < 0 || timeToLive.compareTo(MAX_TIME_TO_LIVE) > 0) {
            throw new IllegalArgumentException("the presigned URL time to live must be between 1 second and 7 days");
        }
    }

    @Override
    public DownloadLink presignDownload(String objectKey) {
        Instant expiresAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(timeToLive);
        try {
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) timeToLive.toSeconds())
                    .build());
            return new DownloadLink(URI.create(url), expiresAt);
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw new StorageUnavailableException("the download URL could not be issued", e);
        }
    }
}
