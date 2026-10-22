package co.edu.corhuila.telemedia.documentgeneration.app;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcIdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage.S3DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentDownloadService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentGenerationService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;
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

    @Bean
    DocumentGeneration documentGeneration(IdempotentDocumentStore store, IdGenerator ids, Clock clock) {
        return new DocumentGenerationService(store, ids, clock);
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
