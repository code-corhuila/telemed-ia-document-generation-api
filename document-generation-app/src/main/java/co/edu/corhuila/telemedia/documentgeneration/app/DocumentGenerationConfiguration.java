package co.edu.corhuila.telemedia.documentgeneration.app;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcIdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage.MinioDocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
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
    MinioClient minioClient(@Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey,
            @Value("${minio.region}") String region) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region(region).build();
    }

    @Bean
    DocumentStorage documentStorage(MinioClient client, @Value("${minio.bucket}") String bucket,
            @Value("${minio.presigned-ttl-seconds}") long timeToLiveSeconds, Clock clock) {
        return new MinioDocumentStorage(client, bucket, Duration.ofSeconds(timeToLiveSeconds), clock);
    }

    @Bean
    DocumentDownloads documentDownloads(DocumentQueries queries, DocumentStorage storage) {
        return new DocumentDownloadService(queries, storage);
    }
}
