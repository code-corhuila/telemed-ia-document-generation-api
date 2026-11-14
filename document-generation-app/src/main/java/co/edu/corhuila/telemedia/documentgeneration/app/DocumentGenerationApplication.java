package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root of the Document Generation API.
 *
 * <p>It is the only place that knows every concrete type. Component scanning
 * starts at the service base package so the adapters module is wired in.
 */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.telemedia.documentgeneration")
public class DocumentGenerationApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentGenerationApplication.class, args);
    }
}
