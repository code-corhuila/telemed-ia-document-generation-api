package co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.http.Method;

/**
 * PDFs live in one bucket of S3-compatible object storage (ADR-021) and are downloaded by
 * the browser through a presigned GET URL (ADR-015). The client is the MinIO Java SDK used
 * as a generic S3 client. With the region configured, signing is computed locally.
 *
 * <p>{@code expiresAt} is taken before signing and truncated to the second, so it is never
 * later than the real expiry of the URL.
 */
public final class S3DocumentStorage implements DocumentStorage {

    /** Another replica created the bucket between the check and the creation. */
    private static final Set<String> ALREADY_CREATED = Set.of("BucketAlreadyOwnedByYou", "BucketAlreadyExists");

    /** S3 limit for presigned URLs. */
    static final Duration MAX_TIME_TO_LIVE = Duration.ofDays(7);

    private final MinioClient client;
    private final MinioClient signer;
    private final String bucket;
    private final Duration timeToLive;
    private final Clock clock;

    /**
     * @param client storage operations, through the internal endpoint
     * @param signer only signs download URLs, configured with the public endpoint the browser
     *        reaches (ADR-021); it never sends a request
     */
    public S3DocumentStorage(MinioClient client, MinioClient signer, String bucket, Duration timeToLive,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.signer = Objects.requireNonNull(signer, "signer");
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
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
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

    /**
     * Creates the bucket when it does not exist (ADR-021), so no environment needs a manual
     * step. Safe to repeat and to run from several replicas at the same time.
     *
     * @throws StorageUnavailableException if the server cannot be reached or refuses it
     */
    public void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (ErrorResponseException e) {
            if (!ALREADY_CREATED.contains(e.errorResponse().code())) {
                throw new StorageUnavailableException("the bucket " + bucket + " could not be created", e);
            }
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw new StorageUnavailableException("the bucket " + bucket + " could not be created", e);
        }
    }
}
