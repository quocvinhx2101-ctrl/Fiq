package io.fiq.server.service;

import io.fiq.domain.MaintenancePlanner;
import io.fiq.domain.OperationState;
import io.fiq.domain.OperationType;
import io.fiq.domain.Role;
import io.fiq.domain.VacuumPreflightEvidence;
import io.fiq.engine.spark.SparkJobState;
import io.fiq.engine.spark.SparkVacuumPreflightRequest;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OperationService {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject FiqEventBus events;
    @Inject HealthAssessmentService health;
    @Inject SparkClientProvider sparkClients;
    @Inject StructuredArtifactReader artifacts;

    @ConfigProperty(name = "fiq.results.prefix")
    String resultsPrefix;

    private final MaintenancePlanner planner = new MaintenancePlanner();

    public ApiModels.OperationView plan(UUID workspaceId, ApiModels.PlanRequest request) {
        access.require(Role.OPERATOR);
        // Planning always observes current facts; policies never influence what assessment
        // measures.
        health.refresh(workspaceId, request.tableId());
        var snapshot = store.loadSnapshot(workspaceId, request.tableId());
        var assessment = store.loadHealth(workspaceId, request.tableId(), snapshot.table());
        var policy = store.loadPolicy(workspaceId, request.policyId());
        if (!policy.selector()
                .matches(snapshot.table(), store.loadTableTags(workspaceId, request.tableId()))) {
            throw new ApiException(
                    422,
                    "FIQ_POLICY_SELECTOR_MISMATCH",
                    "The policy selector does not match this table");
        }
        var plan =
                request.operationType() == OperationType.VACUUM_FULL
                        ? planner.plan(
                                snapshot,
                                assessment,
                                policy,
                                request.operationType(),
                                vacuumPreflight(workspaceId, request.tableId(), snapshot, policy))
                        : planner.plan(snapshot, assessment, policy, request.operationType());
        var saved =
                store.savePlan(
                        workspaceId,
                        request.tableId(),
                        request.policyId(),
                        plan,
                        request.idempotencyKey());
        if (request.operationType() == OperationType.VACUUM_FULL) {
            store.attachPreflight(
                    workspaceId,
                    saved.id(),
                    plan.evaluation().observations(),
                    String.valueOf(plan.evaluation().observations().get("candidateHash")));
            saved = store.operation(workspaceId, saved.id());
        }
        store.audit(
                workspaceId,
                "OPERATION_PLANNED",
                plan.executable() ? "INFO" : "WARN",
                access.principal(),
                "operation",
                saved.id().toString(),
                Map.of(
                        "operationType", request.operationType().name(),
                        "executable", plan.executable(),
                        "basedOnVersion", plan.basedOnVersion()));
        events.publish(
                workspaceId,
                "OPERATION_PLANNED",
                Map.of("operationId", saved.id(), "state", saved.state().name()));
        return saved;
    }

    private VacuumPreflightEvidence vacuumPreflight(
            UUID workspaceId,
            UUID tableId,
            io.fiq.domain.DeltaTableSnapshot snapshot,
            io.fiq.domain.MaintenancePolicy policy) {
        var config =
                policy.operationConfigs()
                        .getOrDefault(
                                OperationType.VACUUM_FULL,
                                new io.fiq.domain.MaintenancePolicy.OperationConfig(
                                        MaintenancePlanner.SAFE_VACUUM_RETENTION_HOURS,
                                        java.util.List.of(),
                                        "",
                                        "",
                                        false,
                                        false));
        var sample = "true".equalsIgnoreCase(snapshot.properties().get("fiq.sample"));
        var allowUnsafe = sample && config.retentionHours() == 0;
        if (config.retentionHours() < MaintenancePlanner.SAFE_VACUUM_RETENTION_HOURS
                && !allowUnsafe) {
            throw new ApiException(
                    422,
                    "FIQ_UNSAFE_RETENTION_NOT_QUALIFIED",
                    "Retention below 168 hours is qualified only for isolated zero-hour sample tables");
        }
        var connection = store.tableConnection(workspaceId, tableId);
        var sparkConf = new LinkedHashMap<String, String>();
        connection.options().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("spark."))
                .forEach(entry -> sparkConf.put(entry.getKey(), entry.getValue()));
        var preflightId = UUID.randomUUID();
        var prefix = resultsPrefix.replaceAll("/+$", "") + "/preflight/" + preflightId;
        var spark = sparkClients.forEndpoint(connection.engineUri());
        var jobId =
                spark.submitVacuumPreflight(
                        new SparkVacuumPreflightRequest(
                                preflightId,
                                snapshot.table().executionTarget(),
                                snapshot.version(),
                                config.retentionHours(),
                                prefix,
                                allowUnsafe,
                                sparkConf));
        var deadline = Instant.now().plusSeconds(600);
        while (Instant.now().isBefore(deadline)) {
            var status = spark.status(jobId);
            if (!status.state().terminal()) {
                pause();
                continue;
            }
            if (status.state() != SparkJobState.SUCCEEDED) {
                throw new ApiException(
                        422,
                        "FIQ_VACUUM_PREFLIGHT_FAILED",
                        "VACUUM FULL DRY RUN failed: " + status.errorMessage());
            }
            var artifact = artifacts.read(prefix, connection.options());
            var result = artifact.result();
            if (!preflightId.toString().equals(result.get("runId"))) {
                throw new IllegalStateException("VACUUM preflight run id does not match");
            }
            return new VacuumPreflightEvidence(
                    number(result, "plannedVersion").longValue(),
                    number(result, "candidateCount").longValue(),
                    nullableLong(result.get("candidateBytes")),
                    String.valueOf(result.get("candidateHash")),
                    number(result, "retentionHours").longValue(),
                    Instant.parse(String.valueOf(result.get("plannedAt"))));
        }
        spark.cancel(jobId);
        throw new ApiException(
                504, "FIQ_VACUUM_PREFLIGHT_TIMEOUT", "VACUUM FULL DRY RUN timed out");
    }

    private static Number number(Map<String, Object> values, String key) {
        var value = values.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalStateException("VACUUM preflight is missing: " + key);
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VACUUM preflight was interrupted", exception);
        }
    }

    public ApiModels.OperationView queue(UUID workspaceId, UUID operationId) {
        access.require(Role.OPERATOR);
        var operation = store.operation(workspaceId, operationId);
        if (!operation.reasons().isEmpty()) {
            throw new ApiException(
                    422,
                    "FIQ_PLAN_NOT_EXECUTABLE",
                    "Resolve the plan blockers before execution: "
                            + String.join("; ", operation.reasons()));
        }
        if (operation.approvalRequired() && operation.state() == OperationState.AWAITING_APPROVAL) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_APPROVAL_REQUIRED",
                    "This operation must be approved before it can be queued");
        }
        var queued =
                store.transition(
                        workspaceId,
                        operationId,
                        OperationState.PLANNED,
                        OperationState.QUEUED,
                        null,
                        null,
                        null);
        publishState(workspaceId, queued);
        return queued;
    }

    public ApiModels.OperationView approve(
            UUID workspaceId, UUID operationId, ApiModels.ApprovalRequest request) {
        access.require(Role.APPROVER);
        if (!"APPROVE".equalsIgnoreCase(request.decision())
                && !"REJECT".equalsIgnoreCase(request.decision())) {
            throw new ApiException(
                    Response.Status.BAD_REQUEST,
                    "FIQ_INVALID_APPROVAL_DECISION",
                    "Decision must be APPROVE or REJECT");
        }
        var updated =
                store.approve(
                        workspaceId,
                        operationId,
                        access.principal(),
                        request.decision(),
                        request.comment());
        store.audit(
                workspaceId,
                "OPERATION_" + (updated.state() == OperationState.QUEUED ? "APPROVED" : "REJECTED"),
                "INFO",
                access.principal(),
                "operation",
                operationId.toString(),
                Map.of("comment", request.comment() == null ? "" : request.comment()));
        publishState(workspaceId, updated);
        return updated;
    }

    public ApiModels.OperationView cancel(UUID workspaceId, UUID operationId) {
        access.require(Role.OPERATOR);
        var operation = store.operation(workspaceId, operationId);
        var target =
                operation.state() == OperationState.RUNNING
                        ? OperationState.CANCELLING
                        : OperationState.CANCELLED;
        var updated =
                store.transition(
                        workspaceId, operationId, operation.state(), target, null, null, null);
        publishState(workspaceId, updated);
        return updated;
    }

    public ApiModels.OperationView retry(UUID workspaceId, UUID operationId) {
        access.require(Role.OPERATOR);
        var previous = store.operation(workspaceId, operationId);
        if (previous.state() != OperationState.FAILED
                && previous.state() != OperationState.SKIPPED
                && previous.state() != OperationState.CANCELLED) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_RETRY_NOT_ALLOWED",
                    "Only failed, skipped, or cancelled operations can be retried");
        }
        health.refresh(workspaceId, previous.tableId());
        return plan(
                workspaceId,
                new ApiModels.PlanRequest(
                        previous.tableId(),
                        previous.policyId(),
                        previous.operationType(),
                        "retry:" + previous.id() + ":" + UUID.randomUUID()));
    }

    private void publishState(UUID workspaceId, ApiModels.OperationView operation) {
        events.publish(
                workspaceId,
                "OPERATION_STATE_CHANGED",
                Map.of("operationId", operation.id(), "state", operation.state().name()));
    }
}
