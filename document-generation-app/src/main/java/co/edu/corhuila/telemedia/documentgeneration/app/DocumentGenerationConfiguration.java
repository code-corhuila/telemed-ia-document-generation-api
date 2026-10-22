package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;

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
    DocumentQueries documentQueries(ConsultationDocumentRepository repository) {
        return new DocumentQueryService(repository);
    }
}
