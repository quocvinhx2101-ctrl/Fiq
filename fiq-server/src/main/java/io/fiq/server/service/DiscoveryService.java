package io.fiq.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fiq.delta.DeltaPathDiscovery;
import io.fiq.domain.PathTarget;
import io.fiq.domain.Role;
import io.fiq.engine.spark.SparkCatalogDiscoveryRequest;
import io.fiq.engine.spark.SparkJobState;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DiscoveryService {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject FiqEventBus events;
    @Inject SparkClientProvider sparkClients;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "fiq.sample.enabled", defaultValue = "false")
    boolean sampleEnabled;

    @ConfigProperty(name = "fiq.s3.endpoint", defaultValue = "")
    String s3Endpoint;

    private final DeltaPathDiscovery pathDiscovery = new DeltaPathDiscovery();

    public ApiModels.DiscoveryRunView discover(
            UUID workspaceId, UUID connectionId, ApiModels.DiscoveryRequest request) {
        access.require(Role.ADMIN);
        return discoverInternal(workspaceId, connectionId, request);
    }

    public ApiModels.DiscoveryRunView discoverSystem(
            UUID workspaceId, UUID connectionId, ApiModels.DiscoveryRequest request) {
        return discoverInternal(workspaceId, connectionId, request);
    }

    private ApiModels.DiscoveryRunView discoverInternal(
            UUID workspaceId, UUID connectionId, ApiModels.DiscoveryRequest request) {
        var connection = store.connection(workspaceId, connectionId);
        if (!"PATH".equalsIgnoreCase(connection.catalogType())
                && !"HMS".equalsIgnoreCase(connection.catalogType())) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_DISCOVERY_SOURCE_UNSUPPORTED",
                    "Only PATH and HMS discovery are qualified in Phase 1");
        }
        if ("HMS".equalsIgnoreCase(connection.catalogType())) {
            return discoverHms(workspaceId, connection, request);
        }
        var rootUri =
                request.rootUri() == null || request.rootUri().isBlank()
                        ? connection.warehouseUri()
                        : request.rootUri();
        if (rootUri == null || rootUri.isBlank()) {
            throw new ApiException(
                    422,
                    "FIQ_DISCOVERY_ROOT_REQUIRED",
                    "PATH discovery requires an explicit bounded rootUri");
        }
        var depth = request.maxDepth() == null ? 4 : request.maxDepth();
        var tables = request.maxTables() == null ? 10_000 : request.maxTables();
        var timeout = request.timeoutSeconds() == null ? 1800 : request.timeoutSeconds();
        var run =
                store.createDiscoveryRun(
                        workspaceId, connectionId, rootUri, depth, tables, timeout);
        try {
            var found =
                    pathDiscovery.discover(
                            PathTarget.of(rootUri),
                            depth,
                            tables,
                            Duration.ofSeconds(timeout),
                            hadoopOptions(connection));
            var catalogAlias = connection.options().getOrDefault("catalogAlias", "path");
            var namespace =
                    connection
                            .options()
                            .getOrDefault("namespacePrefix", safeName(connection.name()));
            for (var table : found) {
                var sample =
                        sampleEnabled
                                && "fiq-samples".equalsIgnoreCase(table.target().uri().getHost());
                store.upsertPathTable(
                        workspaceId, connection, catalogAlias, namespace, table, sample);
            }
            var completed = store.completeDiscovery(workspaceId, run.id(), found.size());
            events.publish(
                    workspaceId,
                    "DISCOVERY_COMPLETED",
                    Map.of("runId", run.id(), "tablesFound", found.size()));
            return completed;
        } catch (RuntimeException exception) {
            store.failDiscovery(
                    workspaceId, run.id(), "FIQ_DISCOVERY_FAILED", exception.getMessage());
            throw exception;
        }
    }

    private ApiModels.DiscoveryRunView discoverHms(
            UUID workspaceId,
            ApiModels.ConnectionView connection,
            ApiModels.DiscoveryRequest request) {
        var timeout = request.timeoutSeconds() == null ? 1800 : request.timeoutSeconds();
        var run = store.createDiscoveryRun(workspaceId, connection.id(), null, 0, 10_000, timeout);
        try {
            var spark = sparkClients.forEndpoint(connection.engineUri());
            var catalog = connection.options().getOrDefault("catalog", "spark_catalog");
            var conf = new LinkedHashMap<String, String>();
            connection.options().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("spark."))
                    .forEach(entry -> conf.put(entry.getKey(), entry.getValue()));
            var jobId =
                    spark.submitCatalogDiscovery(
                            new SparkCatalogDiscoveryRequest(run.id(), catalog, conf));
            var deadline = Instant.now().plusSeconds(timeout);
            while (Instant.now().isBefore(deadline)) {
                var status = spark.status(jobId);
                if (!status.state().terminal()) {
                    pause();
                    continue;
                }
                if (status.state() != SparkJobState.SUCCEEDED) {
                    throw new IllegalStateException(
                            "HMS discovery Spark job failed: " + status.errorMessage());
                }
                var payload = catalogPayload(status.log());
                var tables = objectList(payload.get("tables"));
                for (var table : tables) {
                    store.upsertCatalogTable(
                            workspaceId,
                            connection,
                            catalog,
                            stringList(table.get("namespace")),
                            String.valueOf(table.get("table")),
                            String.valueOf(table.get("location")),
                            number(table.get("version")).longValue(),
                            number(table.get("minReaderVersion")).intValue(),
                            number(table.get("minWriterVersion")).intValue(),
                            stringList(table.get("tableFeatures")),
                            stringList(table.get("partitionColumns")),
                            stringMap(table.get("properties")),
                            Boolean.TRUE.equals(table.get("sample")));
                }
                return store.completeDiscovery(workspaceId, run.id(), tables.size());
            }
            spark.cancel(jobId);
            throw new IllegalStateException("HMS discovery timed out");
        } catch (RuntimeException exception) {
            store.failDiscovery(
                    workspaceId, run.id(), "FIQ_HMS_DISCOVERY_FAILED", exception.getMessage());
            throw exception;
        }
    }

    private Map<String, Object> catalogPayload(List<String> log) {
        var marker = "FIQ_CATALOG_DISCOVERY:";
        var encoded =
                log.stream()
                        .filter(line -> line.contains(marker))
                        .reduce((left, right) -> right)
                        .map(line -> line.substring(line.indexOf(marker) + marker.length()).trim())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "HMS discovery result was not emitted"));
        try {
            return mapper.readValue(Base64.getDecoder().decode(encoded), new TypeReference<>() {});
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("HMS discovery result is invalid", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        var result = new LinkedHashMap<String, String>();
        map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalStateException("HMS discovery result contains an invalid number");
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HMS discovery was interrupted", exception);
        }
    }

    private Map<String, String> hadoopOptions(ApiModels.ConnectionView connection) {
        var options = new LinkedHashMap<String, String>();
        connection.options().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("fs."))
                .forEach(entry -> options.put(entry.getKey(), entry.getValue()));
        if (!s3Endpoint.isBlank()) {
            options.put("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
            options.put("fs.s3a.endpoint", s3Endpoint);
            options.put("fs.s3a.path.style.access", "true");
            options.put("fs.s3a.connection.ssl.enabled", "false");
        }
        return Map.copyOf(options);
    }

    private static String safeName(String value) {
        var normalized = value.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        return normalized.isBlank() ? "default" : normalized;
    }
}
