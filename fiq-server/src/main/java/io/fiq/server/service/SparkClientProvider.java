package io.fiq.server.service;

import io.fiq.engine.spark.LivyExecutionClient;
import io.fiq.engine.spark.SparkExecutionClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.net.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SparkClientProvider {
    @ConfigProperty(name = "fiq.livy.url")
    String livyUrl;

    @ConfigProperty(name = "fiq.livy.job-jar")
    String jobJar;

    @ConfigProperty(name = "fiq.livy.main-class")
    String mainClass;

    @Produces
    @ApplicationScoped
    SparkExecutionClient sparkClient() {
        return new LivyExecutionClient(URI.create(livyUrl), jobJar, mainClass);
    }
}
