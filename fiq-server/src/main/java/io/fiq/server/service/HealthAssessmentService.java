package io.fiq.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fiq.delta.DeltaKernelInspector;
import io.fiq.domain.DeltaCapabilities;
import io.fiq.domain.DeltaTableSnapshot;
import io.fiq.domain.HealthAssessment;
import io.fiq.domain.HealthCompleteness;
import io.fiq.domain.HealthDimension;
import io.fiq.domain.HealthDimensionMetadata;
import io.fiq.domain.HealthIssue;
import io.fiq.domain.HealthSeverity;
import io.fiq.domain.OperationType;
import io.fiq.domain.PathTarget;
import io.fiq.domain.Role;
import io.fiq.domain.TableAccessMode;
import io.fiq.engine.spark.SparkAssessmentRequest;
import io.fiq.engine.spark.SparkJobState;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HealthAssessmentService {
    private static final String KERNEL_PROVENANCE = "delta-kernel-4.0.1";

    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject FiqEventBus events;
    @Inject SparkClientProvider sparkClients;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "fiq.s3.endpoint", defaultValue = "")
    String s3Endpoint;

    private final DeltaKernelInspector inspector = new DeltaKernelInspector();

    public ApiModels.HealthView refresh(UUID workspaceId, UUID tableId) {
        access.require(Role.OPERATOR);
        return refresh(workspaceId, tableId, access.principal());
    }

    public ApiModels.HealthView refreshSystem(UUID workspaceId, UUID tableId) {
        return refresh(workspaceId, tableId, "fiq-scheduler");
    }

    private ApiModels.HealthView refresh(UUID workspaceId, UUID tableId, String principal) {
        var snapshot = store.loadSnapshot(workspaceId, tableId);
        if (snapshot.accessMode() == TableAccessMode.CATALOG_MANAGED) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_CATALOG_ASSESSMENT_REQUIRED",
                    "Catalog-managed tables must be assessed through their catalog-qualified Spark session");
        }
        var location =
                snapshot.table()
                        .location()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                Response.Status.CONFLICT,
                                                "FIQ_TABLE_LOCATION_MISSING",
                                                "Classic Delta inspection requires a registered table location"));
        var metrics = inspector.inspect(location, hadoopOptions(workspaceId, tableId));
        var sparkFacts = sparkAssessment(workspaceId, tableId, snapshot);
        var sparkVersion = number(sparkFacts, "version").longValue();
        if (sparkVersion != metrics.version()) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_ASSESSMENT_STALE",
                    "The table changed while FIQ was collecting assessment facts");
        }
        var observedAt = Instant.now();
        var observedSnapshot =
                new DeltaTableSnapshot(
                        snapshot.table(),
                        snapshot.accessMode(),
                        metrics.version(),
                        metrics.snapshotTimestamp(),
                        number(sparkFacts, "minReaderVersion").intValue(),
                        number(sparkFacts, "minWriterVersion").intValue(),
                        Set.copyOf(stringList(sparkFacts.get("tableFeatures"))),
                        metrics.partitionColumns(),
                        metrics.clusteringColumns(),
                        snapshot.properties(),
                        snapshot.catalogMaintenanceAllowed(),
                        snapshot.filesystemVisibleStateCurrent());
        var capabilities = new DeltaCapabilities().evaluate(observedSnapshot);
        var optimizeSupported = capabilities.get(OperationType.OPTIMIZE_BINPACK).supported();
        var mutationQualified = Boolean.TRUE.equals(sparkFacts.get("mutationQualified"));
        var dimensions =
                dimensions(
                        metrics.version(),
                        observedAt,
                        metrics.clusteringColumns(),
                        Boolean.TRUE.equals(sparkFacts.get("tombstoneSizeComplete")),
                        mutationQualified);
        var issues = incompleteEvidenceIssues(dimensions);
        var assessed =
                new HealthAssessment(
                        snapshot.table(),
                        metrics.version(),
                        observedAt,
                        dimensions,
                        new HealthAssessment.FileLayout(
                                metrics.fileCount(),
                                metrics.totalBytes(),
                                metrics.minFileBytes(),
                                metrics.maxFileBytes(),
                                metrics.averageFileBytes(),
                                new HealthAssessment.QuantileEstimate(
                                        metrics.approximateMedianFileBytes(),
                                        metrics.medianErrorBoundBytes(),
                                        metrics.medianMethod()),
                                metrics.fileSizeHistogram(),
                                metrics.partitionCount(),
                                metrics.partitionSkew()),
                        new HealthAssessment.DeletionVectors(
                                metrics.filesWithDeletionVectors(),
                                metrics.deletionVectorBytes(),
                                metrics.deletedRowCount(),
                                null),
                        new HealthAssessment.TransactionLog(
                                metrics.version(),
                                nullableLong(sparkFacts.get("checkpointVersion")),
                                instantMillis(sparkFacts.get("checkpointTimestamp")),
                                nullableString(sparkFacts.get("checkpointType")),
                                nullableLong(sparkFacts.get("commitsSinceCheckpoint")),
                                number(sparkFacts, "logFileCountSinceCheckpoint").longValue(),
                                number(sparkFacts, "logBytesSinceCheckpoint").longValue(),
                                null),
                        new HealthAssessment.StorageRetention(
                                number(sparkFacts, "tombstoneCount").longValue(),
                                nullableLong(sparkFacts.get("tombstoneBytes")),
                                instantMillis(sparkFacts.get("oldestDeletionTimestamp")),
                                instantMillis(sparkFacts.get("newestDeletionTimestamp")),
                                longList(sparkFacts.get("tombstoneAgeBucketHours")),
                                longList(sparkFacts.get("tombstoneAgeBucketCounts"))),
                        new HealthAssessment.Clustering(
                                metrics.partitionColumns(),
                                !metrics.clusteringColumns().isEmpty(),
                                metrics.clusteringColumns(),
                                null,
                                metrics.clusteringColumns().isEmpty(),
                                optimizeSupported,
                                false),
                        new HealthAssessment.Protocol(
                                observedSnapshot.minReaderVersion(),
                                observedSnapshot.minWriterVersion(),
                                observedSnapshot.tableFeatures().stream().sorted().toList(),
                                snapshot.accessMode(),
                                nullableString(sparkFacts.get("sparkVersion")),
                                nullableString(sparkFacts.get("deltaVersion")),
                                true,
                                mutationQualified
                                        && capabilities.values().stream()
                                                .anyMatch(value -> value.supported()),
                                snapshot.filesystemVisibleStateCurrent()),
                        issues);
        // debt_score is a legacy UI summary and is deliberately not a maintenance decision input.
        var view = store.saveHealth(workspaceId, tableId, assessed, 0);
        store.audit(
                workspaceId,
                "TABLE_HEALTH_REFRESHED",
                issues.isEmpty() ? "INFO" : "WARN",
                principal,
                "table",
                tableId.toString(),
                Map.of(
                        "observedVersion",
                        metrics.version(),
                        "dimensions",
                        dimensions.values().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                value -> value.dimension().name(),
                                                value -> value.completeness().name()))));
        events.publish(
                workspaceId,
                "TABLE_HEALTH_REFRESHED",
                Map.of("tableId", tableId, "observedVersion", metrics.version()));
        return view;
    }

    private Map<HealthDimension, HealthDimensionMetadata> dimensions(
            long version,
            Instant observedAt,
            List<String> clusteringColumns,
            boolean retentionBytesComplete,
            boolean mutationQualified) {
        var values = new EnumMap<HealthDimension, HealthDimensionMetadata>(HealthDimension.class);
        values.put(
                HealthDimension.FILE_LAYOUT,
                complete(HealthDimension.FILE_LAYOUT, version, observedAt, KERNEL_PROVENANCE));
        values.put(
                HealthDimension.DELETION_VECTORS,
                partial(
                        HealthDimension.DELETION_VECTORS,
                        version,
                        observedAt,
                        KERNEL_PROVENANCE,
                        "Deleted-row ratio is unknown because total row count was not observed"));
        values.put(
                HealthDimension.TRANSACTION_LOG,
                complete(
                        HealthDimension.TRANSACTION_LOG, version, observedAt, "delta-spark-4.0.1"));
        values.put(
                HealthDimension.RETENTION,
                retentionBytesComplete
                        ? complete(
                                HealthDimension.RETENTION, version, observedAt, "delta-spark-4.0.1")
                        : partial(
                                HealthDimension.RETENTION,
                                version,
                                observedAt,
                                "delta-spark-4.0.1",
                                "Some tombstones do not carry extended file-size metadata"));
        values.put(
                HealthDimension.CLUSTERING,
                clusteringColumns.isEmpty()
                        ? complete(
                                HealthDimension.CLUSTERING, version, observedAt, KERNEL_PROVENANCE)
                        : partial(
                                HealthDimension.CLUSTERING,
                                version,
                                observedAt,
                                KERNEL_PROVENANCE,
                                "Liquid-clustering columns were observed but domain freshness is UNKNOWN"));
        values.put(
                HealthDimension.PROTOCOL,
                mutationQualified
                        ? complete(
                                HealthDimension.PROTOCOL, version, observedAt, "delta-spark-4.0.1")
                        : partial(
                                HealthDimension.PROTOCOL,
                                version,
                                observedAt,
                                "delta-spark-runtime-probe",
                                "Mutation runtime is not exactly Spark 4.0.1 and Delta Lake 4.0.1"));
        return Map.copyOf(values);
    }

    private Map<String, Object> sparkAssessment(
            UUID workspaceId, UUID tableId, DeltaTableSnapshot snapshot) {
        var connection = store.tableConnection(workspaceId, tableId);
        var conf = new LinkedHashMap<String, String>();
        connection.options().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("spark."))
                .forEach(entry -> conf.put(entry.getKey(), entry.getValue()));
        var assessmentId = UUID.randomUUID();
        var spark = sparkClients.forEndpoint(connection.engineUri());
        var jobId =
                spark.submitAssessment(
                        new SparkAssessmentRequest(
                                assessmentId, snapshot.table().executionTarget(), conf));
        var deadline = Instant.now().plusSeconds(600);
        while (Instant.now().isBefore(deadline)) {
            var status = spark.status(jobId);
            if (!status.state().terminal()) {
                pause();
                continue;
            }
            if (status.state() != SparkJobState.SUCCEEDED) {
                throw new IllegalStateException(
                        "Spark assessment failed: " + status.errorMessage());
            }
            return assessmentPayload(
                    status.log(), snapshot.table().executionTarget() instanceof PathTarget);
        }
        spark.cancel(jobId);
        throw new IllegalStateException("Spark assessment timed out");
    }

    private Map<String, Object> assessmentPayload(List<String> log, boolean pathTarget) {
        var marker = "FIQ_ASSESSMENT_RESULT:";
        var encoded =
                log.stream()
                        .filter(line -> line.contains(marker))
                        .reduce((left, right) -> right)
                        .map(line -> line.substring(line.indexOf(marker) + marker.length()).trim())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Spark assessment result is missing"));
        try {
            var payload =
                    mapper.readValue(
                            Base64.getDecoder().decode(encoded),
                            new TypeReference<Map<String, Object>>() {});
            var expectedResolution = pathTarget ? "PATH" : "CATALOG";
            if (!expectedResolution.equals(payload.get("resolvedThrough"))) {
                throw new IllegalStateException(
                        "Spark assessment violated execution-target semantics");
            }
            return payload;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Spark assessment result is invalid", exception);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Spark assessment was interrupted", exception);
        }
    }

    private static Number number(Map<String, Object> values, String key) {
        var value = values.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalStateException("Spark assessment is missing numeric fact: " + key);
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instantMillis(Object value) {
        return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(item -> ((Number) item).longValue()).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static HealthDimensionMetadata complete(
            HealthDimension dimension, long version, Instant observedAt, String provenance) {
        return new HealthDimensionMetadata(
                dimension,
                HealthCompleteness.COMPLETE,
                provenance,
                observedAt,
                version,
                Optional.empty());
    }

    private static HealthDimensionMetadata partial(
            HealthDimension dimension,
            long version,
            Instant observedAt,
            String provenance,
            String reason) {
        return new HealthDimensionMetadata(
                dimension,
                HealthCompleteness.PARTIAL,
                provenance,
                observedAt,
                version,
                Optional.of(reason));
    }

    private static List<HealthIssue> incompleteEvidenceIssues(
            Map<HealthDimension, HealthDimensionMetadata> dimensions) {
        var issues = new ArrayList<HealthIssue>();
        for (var value : dimensions.values()) {
            if (value.completeness() == HealthCompleteness.COMPLETE) continue;
            issues.add(
                    new HealthIssue(
                            "FIQ_" + value.dimension().name() + "_INCOMPLETE",
                            value.dimension() == HealthDimension.PROTOCOL
                                    ? HealthSeverity.WARNING
                                    : HealthSeverity.INFO,
                            value.dimension().name(),
                            value.incompleteReason().orElse("Evidence is incomplete"),
                            "Collect the missing evidence before planning operations that require this dimension"));
        }
        return List.copyOf(issues);
    }

    private Map<String, String> hadoopOptions(UUID workspaceId, UUID tableId) {
        var options = new LinkedHashMap<String, String>();
        store.tableConnectionOptions(workspaceId, tableId).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("fs."))
                .forEach(entry -> options.put(entry.getKey(), entry.getValue()));
        if (!s3Endpoint.isBlank()) {
            options.put("fs.s3a.endpoint", s3Endpoint);
            options.put("fs.s3a.path.style.access", "true");
            options.put("fs.s3a.connection.ssl.enabled", "false");
        }
        return Map.copyOf(options);
    }
}
