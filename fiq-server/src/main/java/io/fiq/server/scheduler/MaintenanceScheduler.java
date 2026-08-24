package io.fiq.server.scheduler;

import io.fiq.domain.CatalogTarget;
import io.fiq.domain.MaintenancePlanner;
import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationState;
import io.fiq.domain.OperationType;
import io.fiq.engine.spark.SparkMaintenanceRequest;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.service.HealthAssessmentService;
import io.fiq.server.service.SparkClientProvider;
import io.fiq.server.service.StructuredArtifactReader;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MaintenanceScheduler {
    @Inject FiqStore store;
    @Inject FiqEventBus events;
    @Inject HealthAssessmentService health;
    @Inject SparkClientProvider sparkClients;
    @Inject StructuredArtifactReader artifacts;

    @ConfigProperty(name = "fiq.scheduler.enabled")
    boolean enabled;

    @ConfigProperty(name = "fiq.scheduler.batch-size")
    int batchSize;

    @ConfigProperty(name = "fiq.scheduler.lease-seconds")
    int leaseSeconds;

    @ConfigProperty(name = "fiq.results.prefix")
    String resultsPrefix;

    private final String owner = instanceOwner();

    @Scheduled(every = "5s", concurrentExecution = ConcurrentExecution.SKIP)
    void tick() {
        if (!enabled) return;
        for (var operation : store.claimActiveOperations(owner, batchSize, leaseSeconds)) {
            poll(operation);
        }
        for (var operation : store.claimQueuedOperations(owner, batchSize, leaseSeconds)) {
            submit(operation);
        }
    }

    private void submit(ApiModels.OperationView operation) {
        try {
            store.updateOperationStep(
                    operation.workspaceId(), operation.id(), "SUBMIT", "RUNNING", Map.of());
            var refreshed = health.refreshSystem(operation.workspaceId(), operation.tableId());
            if (refreshed.observedVersion() != operation.basedOnVersion()) {
                var skipped =
                        store.transition(
                                operation.workspaceId(),
                                operation.id(),
                                OperationState.QUEUED,
                                OperationState.SKIPPED,
                                null,
                                "FIQ_PLAN_STALE",
                                "The table advanced from version "
                                        + operation.basedOnVersion()
                                        + " to "
                                        + refreshed.observedVersion()
                                        + "; reassess and create a new plan");
                publish(operation.workspaceId(), skipped);
                return;
            }
            var policy = store.loadPolicy(operation.workspaceId(), operation.policyId());
            var config = config(policy, operation.operationType());
            var connection = store.tableConnection(operation.workspaceId(), operation.tableId());
            var sparkConf = sparkConf(connection);
            var prefix = resultPrefix(operation.id());
            var candidateHash = string(operation.preflightEvidence().get("candidateHash"), "");
            var candidateCount =
                    number(operation.preflightEvidence().get("candidateCount"), 0).longValue();
            var allowUnsafe =
                    operation.operationType() == OperationType.VACUUM_FULL
                            && config.retentionHours() == 0
                            && store.tableSample(operation.workspaceId(), operation.tableId());
            var request =
                    new SparkMaintenanceRequest(
                            operation.id(),
                            operation.operationType(),
                            operation.executionTarget(),
                            operation.basedOnVersion(),
                            config.retentionHours(),
                            config.zOrderColumns(),
                            config.predicate(),
                            config.inventoryTable(),
                            prefix,
                            candidateHash,
                            candidateCount,
                            allowUnsafe,
                            sparkConf);
            var jobId = sparkClients.forEndpoint(connection.engineUri()).submit(request);
            store.updateOperationStep(
                    operation.workspaceId(),
                    operation.id(),
                    "SUBMIT",
                    "SUCCEEDED",
                    Map.of("livyJobId", jobId, "resultPrefix", prefix));
            store.updateOperationStep(
                    operation.workspaceId(),
                    operation.id(),
                    "EXECUTE",
                    "RUNNING",
                    Map.of("livyJobId", jobId));
            var running =
                    store.transition(
                            operation.workspaceId(),
                            operation.id(),
                            OperationState.QUEUED,
                            OperationState.RUNNING,
                            jobId,
                            null,
                            null);
            publish(operation.workspaceId(), running);
        } catch (RuntimeException exception) {
            store.updateOperationStep(
                    operation.workspaceId(),
                    operation.id(),
                    "SUBMIT",
                    "FAILED",
                    Map.of("message", message(exception)));
            var updated =
                    store.retrySubmission(
                            operation.workspaceId(), operation.id(), message(exception));
            publish(operation.workspaceId(), updated);
        }
    }

    private void poll(ApiModels.OperationView operation) {
        var workspaceId = operation.workspaceId();
        var connection = store.tableConnection(workspaceId, operation.tableId());
        var spark = sparkClients.forEndpoint(connection.engineUri());
        try {
            if (operation.state() == OperationState.CANCELLING) {
                spark.cancel(operation.externalJobId());
                var cancelled =
                        store.transition(
                                workspaceId,
                                operation.id(),
                                OperationState.CANCELLING,
                                OperationState.CANCELLED,
                                null,
                                null,
                                null);
                publish(workspaceId, cancelled);
                return;
            }
            var status = spark.status(operation.externalJobId());
            if (!status.state().terminal()) return;
            if (status.state() != io.fiq.engine.spark.SparkJobState.SUCCEEDED) {
                var target =
                        status.state() == io.fiq.engine.spark.SparkJobState.CANCELLED
                                ? OperationState.CANCELLED
                                : OperationState.FAILED;
                var failed =
                        store.complete(
                                workspaceId,
                                operation.id(),
                                target,
                                Map.of(
                                        "livyJobId",
                                        status.jobId(),
                                        "diagnosticLogTail",
                                        status.log()),
                                target == OperationState.FAILED ? "FIQ_SPARK_JOB_FAILED" : null,
                                status.errorMessage());
                store.updateOperationStep(
                        workspaceId,
                        operation.id(),
                        "EXECUTE",
                        target == OperationState.CANCELLED ? "CANCELLED" : "FAILED",
                        Map.of("diagnosticLogTail", status.log()));
                publish(workspaceId, failed);
                return;
            }
            verifySuccessfulJob(operation, connection);
        } catch (RuntimeException exception) {
            events.publish(
                    workspaceId,
                    "SPARK_STATUS_UNAVAILABLE",
                    Map.of("operationId", operation.id(), "message", message(exception)));
        }
    }

    private void verifySuccessfulJob(
            ApiModels.OperationView operation, ApiModels.ConnectionView connection) {
        store.updateOperationStep(
                operation.workspaceId(),
                operation.id(),
                "EXECUTE",
                "SUCCEEDED",
                Map.of("livyJobId", operation.externalJobId()));
        store.updateOperationStep(
                operation.workspaceId(), operation.id(), "VERIFY", "RUNNING", Map.of());
        StructuredArtifactReader.Artifact artifact;
        try {
            artifact = artifacts.read(resultPrefix(operation.id()), connection.options());
            validateContract(operation, artifact.result());
        } catch (RuntimeException exception) {
            var failed =
                    store.completeVerified(
                            operation.workspaceId(),
                            operation.id(),
                            OperationState.FAILED,
                            Map.of(),
                            Map.of("verified", false, "reason", message(exception)),
                            true,
                            null,
                            null,
                            "FIQ_STRUCTURED_RESULT_INVALID",
                            message(exception));
            store.updateOperationStep(
                    operation.workspaceId(),
                    operation.id(),
                    "VERIFY",
                    "FAILED",
                    Map.of("reason", message(exception)));
            publish(operation.workspaceId(), failed);
            return;
        }

        var result = artifact.result();
        var maintenanceApplied = Boolean.TRUE.equals(result.get("maintenanceApplied"));
        try {
            health.refreshSystem(operation.workspaceId(), operation.tableId());
            var snapshot = store.loadSnapshot(operation.workspaceId(), operation.tableId());
            var assessment =
                    store.loadHealth(
                            operation.workspaceId(), operation.tableId(), snapshot.table());
            var verification = verifyOutcome(operation, result, assessment);
            var completed =
                    store.completeVerified(
                            operation.workspaceId(),
                            operation.id(),
                            OperationState.SUCCEEDED,
                            result,
                            verification,
                            maintenanceApplied,
                            artifact.resultUri(),
                            artifact.checksum(),
                            null,
                            null);
            store.updateOperationStep(
                    operation.workspaceId(), operation.id(), "VERIFY", "SUCCEEDED", verification);
            publish(operation.workspaceId(), completed);
        } catch (RuntimeException exception) {
            var failed =
                    store.completeVerified(
                            operation.workspaceId(),
                            operation.id(),
                            OperationState.FAILED,
                            result,
                            Map.of("verified", false, "reason", message(exception)),
                            maintenanceApplied,
                            artifact.resultUri(),
                            artifact.checksum(),
                            "FIQ_VERIFICATION_FAILED",
                            message(exception));
            store.updateOperationStep(
                    operation.workspaceId(),
                    operation.id(),
                    "VERIFY",
                    "FAILED",
                    Map.of("reason", message(exception)));
            publish(operation.workspaceId(), failed);
        }
    }

    private Map<String, Object> verifyOutcome(
            ApiModels.OperationView operation,
            Map<String, Object> result,
            io.fiq.domain.HealthAssessment assessment) {
        var verification = new LinkedHashMap<String, Object>();
        verification.put("verified", true);
        verification.put("preVersion", number(result.get("preVersion"), -1).longValue());
        verification.put("postVersion", number(result.get("postVersion"), -1).longValue());
        if (operation.operationType() == OperationType.OPTIMIZE_BINPACK) {
            var beforeSmall =
                    number(
                                    operation.policyEvaluation().get("observations")
                                                    instanceof Map<?, ?> map
                                            ? map.get("filesBelowThreshold")
                                            : null,
                                    -1)
                            .longValue();
            var beforeRatio =
                    number(
                                    operation.policyEvaluation().get("observations")
                                                    instanceof Map<?, ?> map
                                            ? map.get("smallFileRatio")
                                            : null,
                                    -1)
                            .doubleValue();
            var policy = store.loadPolicy(operation.workspaceId(), operation.policyId());
            var threshold =
                    config(policy, operation.operationType())
                            .fileLayoutPolicy()
                            .smallFileThresholdBytes();
            var afterSmall = assessment.fileLayout().fileSizeHistogram().countBelow(threshold);
            var afterRatio =
                    assessment.fileLayout().activeFileCount() == 0
                            ? 0
                            : (double) afterSmall / assessment.fileLayout().activeFileCount();
            if (number(result.get("postVersion"), -1).longValue()
                            <= number(result.get("preVersion"), -1).longValue()
                    || (afterSmall >= beforeSmall && afterRatio >= beforeRatio)) {
                throw new IllegalStateException(
                        "OPTIMIZE command completed but file-layout evidence did not improve");
            }
            verification.put("activeFilesAfter", assessment.fileLayout().activeFileCount());
            verification.put("smallFilesBefore", beforeSmall);
            verification.put("smallFilesAfter", afterSmall);
            verification.put("smallFileRatioBefore", beforeRatio);
            verification.put("smallFileRatioAfter", afterRatio);
        } else if (operation.operationType() == OperationType.VACUUM_FULL) {
            var vacuum = map(result.get("vacuum"));
            if (number(vacuum.get("remainingApprovedCandidates"), -1).longValue() != 0) {
                throw new IllegalStateException("Approved VACUUM candidates remain after mutation");
            }
            if (!string(vacuum.get("candidateHash"), "")
                    .equals(string(operation.preflightEvidence().get("candidateHash"), ""))) {
                throw new IllegalStateException(
                        "VACUUM result does not match approved candidate identity");
            }
            verification.put("candidateHash", vacuum.get("candidateHash"));
            verification.put("candidateCount", vacuum.get("candidateCount"));
            verification.put("remainingApprovedCandidates", 0);
        }
        return Map.copyOf(verification);
    }

    private static void validateContract(
            ApiModels.OperationView operation, Map<String, Object> result) {
        if (!operation.id().toString().equals(result.get("runId"))) {
            throw new IllegalStateException("Structured result runId does not match operation");
        }
        if (!operation.operationType().name().equals(result.get("operationType"))) {
            throw new IllegalStateException("Structured result operation type does not match");
        }
        var expected = operation.executionTarget() instanceof CatalogTarget ? "CATALOG" : "PATH";
        if (!expected.equals(result.get("resolvedThrough"))) {
            throw new IllegalStateException(
                    "Structured result used the wrong execution-target semantics");
        }
        if (number(result.get("preVersion"), -1).longValue() != operation.basedOnVersion()) {
            throw new IllegalStateException("Structured result preVersion does not match the plan");
        }
        if (!Boolean.TRUE.equals(result.get("commandSucceeded"))) {
            throw new IllegalStateException("Spark did not report command success");
        }
    }

    private static MaintenancePolicy.OperationConfig config(
            MaintenancePolicy policy, OperationType operation) {
        return policy.operationConfigs()
                .getOrDefault(
                        operation,
                        new MaintenancePolicy.OperationConfig(
                                MaintenancePlanner.SAFE_VACUUM_RETENTION_HOURS,
                                List.of(),
                                "",
                                "",
                                false,
                                false));
    }

    private static Map<String, String> sparkConf(ApiModels.ConnectionView connection) {
        var result = new LinkedHashMap<String, String>();
        connection.options().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("spark."))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private String resultPrefix(UUID id) {
        return resultsPrefix.replaceAll("/+$", "") + "/" + id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Number number(Object value, Number defaultValue) {
        return value instanceof Number number ? number : defaultValue;
    }

    private static String string(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static String message(Throwable value) {
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    private void publish(UUID workspaceId, ApiModels.OperationView operation) {
        events.publish(
                workspaceId,
                "OPERATION_STATE_CHANGED",
                Map.of("operationId", operation.id(), "state", operation.state().name()));
    }

    private static String instanceOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception exception) {
            return "fiq-" + UUID.randomUUID();
        }
    }
}
