package io.fiq.server.service;

import io.fiq.server.api.ApiModels;
import io.fiq.server.persistence.FiqStore;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SampleBootstrap {
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ENVIRONMENT = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID PATH_CONNECTION =
            UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID HMS_CONNECTION =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Inject FiqStore store;
    @Inject DiscoveryService discovery;

    @ConfigProperty(name = "fiq.sample.enabled", defaultValue = "false")
    boolean enabled;

    private volatile boolean complete;

    @Scheduled(every = "10s", delayed = "3s", concurrentExecution = ConcurrentExecution.SKIP)
    void initialize() {
        if (!enabled || complete) return;
        var path =
                store.ensureSampleConnection(
                        PATH_CONNECTION,
                        WORKSPACE,
                        ENVIRONMENT,
                        "Sample PATH",
                        "PATH",
                        null,
                        "s3a://fiq-samples/path",
                        "http://livy:8998",
                        Map.of("catalogAlias", "sample", "namespacePrefix", "path"));
        var hms =
                store.ensureSampleConnection(
                        HMS_CONNECTION,
                        WORKSPACE,
                        ENVIRONMENT,
                        "Sample HMS",
                        "HMS",
                        "thrift://hms:9083",
                        "s3a://fiq-samples/hms",
                        "http://livy:8998",
                        Map.of("catalog", "spark_catalog"));
        discovery.discoverSystem(
                WORKSPACE,
                path.id(),
                new ApiModels.DiscoveryRequest("s3a://fiq-samples/path", 3, 10_000, 1800));
        discovery.discoverSystem(
                WORKSPACE, hms.id(), new ApiModels.DiscoveryRequest(null, 0, 10_000, 1800));
        complete = true;
    }
}
