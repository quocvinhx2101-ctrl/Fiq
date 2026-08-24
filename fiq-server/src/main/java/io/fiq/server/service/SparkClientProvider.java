package io.fiq.server.service;

import io.fiq.engine.spark.LivyExecutionClient;
import io.fiq.engine.spark.SparkExecutionClient;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SparkClientProvider {
    @ConfigProperty(name = "fiq.livy.job-jar")
    String jobJar;

    @ConfigProperty(name = "fiq.livy.main-class")
    String mainClass;

    public SparkExecutionClient forEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "The connection must configure an execution endpoint before Spark is used");
        }
        return new LivyExecutionClient(URI.create(endpoint), jobJar, mainClass);
    }
}
