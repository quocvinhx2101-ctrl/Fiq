package io.fiq.server.service;

import io.fiq.domain.MaintenancePlanner;
import io.fiq.domain.OperationState;
import io.fiq.domain.Role;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OperationService {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject FiqEventBus events;
    @Inject HealthAssessmentService health;

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
        var plan = planner.plan(snapshot, assessment, policy, request.operationType());
        var saved =
                store.savePlan(
                        workspaceId,
                        request.tableId(),
                        request.policyId(),
                        plan,
                        request.idempotencyKey());
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
