package io.fiq.server.scheduler;

import io.fiq.domain.MaintenancePlanner;
import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationState;
import io.fiq.engine.spark.SparkExecutionClient;
import io.fiq.engine.spark.SparkMaintenanceRequest;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.service.HealthAssessmentService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MaintenanceScheduler {
    @Inject FiqStore store;
    @Inject SparkExecutionClient spark;
    @Inject FiqEventBus events;
    @Inject HealthAssessmentService health;

    @ConfigProperty(name = "fiq.scheduler.enabled")
    boolean enabled;

    @ConfigProperty(name = "fiq.scheduler.batch-size")
    int batchSize;

    @ConfigProperty(name = "fiq.scheduler.lease-seconds")
    int leaseSeconds;

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
            var refreshed = health.refreshSystem(operation.workspaceId(), operation.tableId());
            if (refreshed.observedVersion() != operation.basedOnVersion()) {
                var skipped =
                        store.transition(
                                operation.workspaceId(),
                                operation.id(),
                                OperationState.QUEUED,
                                OperationState.SKIPPED,
                                null,
                                "FIQ_PLAN_VERSION_STALE",
                                "The table advanced from version "
                                        + operation.basedOnVersion()
                                        + " to "
                                        + refreshed.observedVersion()
                                        + "; reassess and create a new plan");
                publish(operation.workspaceId(), skipped);
                return;
            }
            var policy = store.loadPolicy(operationWorkspace(operation), operation.policyId());
            var config =
                    policy.operationConfigs()
                            .getOrDefault(
                                    operation.operationType(),
                                    new MaintenancePolicy.OperationConfig(
                                            MaintenancePlanner.SAFE_VACUUM_RETENTION_HOURS,
                                            List.of(),
                                            "",
                                            "",
                                            false,
                                            false));
            var request =
                    new SparkMaintenanceRequest(
                            operation.id(),
                            operation.operationType(),
                            operation.tableName(),
                            operation.basedOnVersion(),
                            config.retentionHours(),
                            config.zOrderColumns(),
                            config.predicate(),
                            config.inventoryTable(),
                            Map.of());
            var jobId = spark.submit(request);
            var workspaceId = operationWorkspace(operation);
            var running =
                    store.transition(
                            workspaceId,
                            operation.id(),
                            OperationState.QUEUED,
                            OperationState.RUNNING,
                            jobId,
                            null,
                            null);
            publish(workspaceId, running);
        } catch (RuntimeException exception) {
            var workspaceId = operationWorkspace(operation);
            var updated =
                    store.retrySubmission(workspaceId, operation.id(), exception.getMessage());
            publish(workspaceId, updated);
        }
    }

    private void poll(ApiModels.OperationView operation) {
        var workspaceId = operationWorkspace(operation);
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
            var target =
                    switch (status.state()) {
                        case SUCCEEDED -> OperationState.SUCCEEDED;
                        case CANCELLED -> OperationState.CANCELLED;
                        case FAILED -> OperationState.FAILED;
                        default ->
                                throw new IllegalStateException("Unexpected terminal Spark state");
                    };
            var evidence = new java.util.LinkedHashMap<String, Object>(status.evidence());
            evidence.put("livyJobId", status.jobId());
            evidence.put("logTail", status.log());
            var completed =
                    store.complete(
                            workspaceId,
                            operation.id(),
                            target,
                            Map.copyOf(evidence),
                            target == OperationState.FAILED ? "FIQ_SPARK_JOB_FAILED" : null,
                            status.errorMessage());
            publish(workspaceId, completed);
            if (target == OperationState.SUCCEEDED) {
                try {
                    health.refreshSystem(workspaceId, operation.tableId());
                } catch (RuntimeException exception) {
                    events.publish(
                            workspaceId,
                            "POST_RUN_ASSESSMENT_FAILED",
                            Map.of(
                                    "operationId",
                                    operation.id(),
                                    "message",
                                    exception.getMessage()));
                }
            }
        } catch (RuntimeException exception) {
            events.publish(
                    workspaceId,
                    "SPARK_STATUS_UNAVAILABLE",
                    Map.of("operationId", operation.id(), "message", exception.getMessage()));
        }
    }

    private UUID operationWorkspace(ApiModels.OperationView operation) {
        return operation.workspaceId();
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
