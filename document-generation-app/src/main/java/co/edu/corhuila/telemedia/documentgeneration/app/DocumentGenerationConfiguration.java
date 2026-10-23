package co.edu.corhuila.telemedia.documentgeneration.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.SchedulingDocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.consultation.HttpConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.pdf.PdfBoxRenderer;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcDocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcIdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage.S3DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentDownloadService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentGenerationService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.PdfGenerationService;
import io.minio.MinioClient;

/**
 * Composition root (Anexo C): the only place that knows the concrete types. The core
 * stays free of Spring; its use cases are created here and receive their ports.
 */
@Configuration
public class DocumentGenerationConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentGenerationConfiguration.class);

    @Bean
    ConsultationDocumentRepository consultationDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcConsultationDocumentRepository(jdbc);
    }

    @Bean
    IdempotentDocumentStore idempotentDocumentStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        return new JdbcIdempotentDocumentStore(jdbc, transactions);
    }

    @Bean
    IdGenerator idGenerator() {
        return new UuidGenerator();
    }

    @Bean
    DocumentQueries documentQueries(ConsultationDocumentRepository repository) {
        return new DocumentQueryService(repository);
    }

    /** POST /generate, then the background generation of the PENDING document (DEC-006). */
    @Bean
    DocumentGeneration documentGeneration(IdempotentDocumentStore store, IdGenerator ids, Clock clock,
            BackgroundPdfGenerator generator) {
        return new SchedulingDocumentGeneration(new DocumentGenerationService(store, ids, clock), generator,
                () -> Optional.ofNullable(MDC.get(CorrelationFilter.MDC_KEY))
                        .orElseGet(() -> UUID.randomUUID().toString()));
    }

    @Bean
    DocumentStatusStore documentStatusStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        return new JdbcDocumentStatusStore(jdbc, transactions);
    }

    @Bean
    PdfRenderer pdfRenderer(@Value("${generation.time-zone}") String timeZone) {
        return new PdfBoxRenderer(ZoneId.of(timeZone));
    }

    @Bean
    ConsultationSummaryReader consultationSummaryReader(@Value("${consultation.base-url}") String baseUrl,
            @Value("${consultation.service-token}") String serviceToken,
            @Value("${consultation.request-timeout-ms}") long requestTimeoutMs,
            @Value("${consultation.max-attempts}") int maxAttempts,
            @Value("${consultation.backoff-base-ms}") long backoffBaseMs,
            @Value("${consultation.backoff-cap-ms}") long backoffCapMs) {
        Duration requestTimeout = Duration.ofMillis(requestTimeoutMs);
        HttpClient http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        return new HttpConsultationSummaryReader(http, new HttpConsultationSummaryReader.Settings(
                URI.create(baseUrl), serviceToken, requestTimeout, maxAttempts, Duration.ofMillis(backoffBaseMs),
                Duration.ofMillis(backoffCapMs)));
    }

    @Bean
    DocumentPdfGeneration documentPdfGeneration(ConsultationDocumentRepository repository,
            DocumentStatusStore statusStore, ConsultationSummaryReader summaries, PdfRenderer renderer,
            S3DocumentStorage storage, IdGenerator ids, Clock clock,
            @Value("${generation.attempt-timeout-seconds}") long attemptTimeoutSeconds) {
        return new PdfGenerationService(repository, statusStore, summaries, renderer, storage, ids, clock,
                Duration.ofSeconds(attemptTimeoutSeconds));
    }

    /**
     * Bounded pool of the background attempts. On shutdown, waiting retries are dropped and
     * running attempts are not waited for: the documents keep their stored state and the
     * resume sweep of the next start continues them.
     */
    @Bean(destroyMethod = "shutdown")
    ScheduledThreadPoolExecutor pdfGenerationExecutor(@Value("${generation.executor-threads}") int threads) {
        AtomicInteger number = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, task -> {
            Thread thread = new Thread(task, "pdf-generation-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean
    BackgroundPdfGenerator backgroundPdfGenerator(DocumentPdfGeneration generation,
            ScheduledThreadPoolExecutor pdfGenerationExecutor,
            @Value("${generation.max-in-flight}") int maxInFlight,
            @Value("${generation.resume-batch-size}") int resumeBatchSize,
            @Value("${generation.resume-interval-seconds}") long resumeIntervalSeconds,
            @Value("${generation.retry-backoff-base-ms}") long backoffBaseMs,
            @Value("${generation.retry-backoff-cap-ms}") long backoffCapMs) {
        BackgroundPdfGenerator.Settings settings = new BackgroundPdfGenerator.Settings(maxInFlight,
                resumeBatchSize, Duration.ofSeconds(resumeIntervalSeconds), Duration.ofMillis(backoffBaseMs),
                Duration.ofMillis(backoffCapMs));
        return new BackgroundPdfGenerator(generation, settings,
                (task, delay) -> pdfGenerationExecutor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS),
                () -> ThreadLocalRandom.current().nextDouble(), () -> UUID.randomUUID().toString());
    }

    /** DEC-006: documents a restart left behind are resumed from startup on. */
    @Bean
    ApplicationRunner startBackgroundGeneration(BackgroundPdfGenerator generator,
            @Value("${generation.background-enabled}") boolean enabled) {
        return arguments -> {
            if (enabled) {
                generator.start();
            }
        };
    }

    @Bean
    S3DocumentStorage documentStorage(@Value("${storage.endpoint}") String endpoint,
            @Value("${storage.public-endpoint}") String publicEndpoint,
            @Value("${storage.access-key}") String accessKey,
            @Value("${storage.secret-key}") String secretKey,
            @Value("${storage.region}") String region,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.presigned-ttl-seconds}") long timeToLiveSeconds, Clock clock) {
        MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey, secretKey).region(region).build();
        MinioClient signer = MinioClient.builder().endpoint(publicEndpoint)
                .credentials(accessKey, secretKey).region(region).build();
        return new S3DocumentStorage(client, signer, bucket, Duration.ofSeconds(timeToLiveSeconds), clock);
    }

    /**
     * ADR-021: the bucket is created at startup when missing. A storage that is down does not
     * stop the service (ADR-015: it affects downloads only); the next start creates it.
     */
    @Bean
    ApplicationRunner ensureStorageBucket(S3DocumentStorage storage) {
        return arguments -> {
            try {
                storage.ensureBucket();
            } catch (StorageUnavailableException e) {
                LOG.warn("object storage unavailable at startup; the bucket was not checked", e);
            }
        };
    }

    @Bean
    DocumentDownloads documentDownloads(DocumentQueries queries, S3DocumentStorage storage) {
        return new DocumentDownloadService(queries, storage);
    }
}
