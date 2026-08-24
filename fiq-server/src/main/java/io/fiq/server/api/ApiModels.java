package io.fiq.server.api;

import io.fiq.domain.ExecutionTarget;
import io.fiq.domain.HealthCompleteness;
import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationState;
import io.fiq.domain.OperationType;
import io.fiq.domain.TableAccessMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {
    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Workspace(UUID id, String name, String timezone) {}

    public record Environment(UUID id, String name, boolean production) {}

    public record Bootstrap(
            Workspace workspace,
            Environment environment,
            String role,
            String timezone,
            Instant serverTime,
            String version) {}

    public record Overview(
            long tables,
            long warningTables,
            long criticalTables,
            long activeOperations,
            long failedOperations,
            long pendingApprovals,
            long reclaimableBytes,
            Instant refreshedAt) {}

    public record TableSummary(
            UUID id,
            String qualifiedName,
            ExecutionTarget executionTarget,
            boolean sample,
            String discoveryStatus,
            String environment,
            String catalog,
            TableAccessMode accessMode,
            long currentVersion,
            long fileCount,
            long totalBytes,
            double debtScore,
            String severity,
            HealthCompleteness healthCompleteness,
            Instant refreshedAt,
            Set<String> features,
            String readOnlyReason) {}

    public record TableDetail(
            TableSummary summary,
            String location,
            List<String> namespace,
            List<String> partitionColumns,
            List<String> clusteringColumns,
            Map<String, String> properties,
            Map<String, String> tags,
            Map<OperationType, CapabilityView> capabilities) {}

    public record CapabilityView(boolean supported, boolean approvalRequired, String reason) {}

    public record HealthView(
            UUID id,
            UUID tableId,
            long observedVersion,
            Instant assessedAt,
            String provenance,
            HealthCompleteness completeness,
            String staleReason,
            Map<String, Map<String, Object>> dimensions,
            Map<String, Object> fileLayout,
            Map<String, Object> deletionVectors,
            Map<String, Object> transactionLog,
            Map<String, Object> storageRetention,
            Map<String, Object> clustering,
            Map<String, Object> protocol,
            double debtScore,
            List<Map<String, Object>> issues) {}

    public record PolicySummary(
            UUID id,
            String name,
            String description,
            boolean enabled,
            String cron,
            String timezone,
            Set<OperationType> operations,
            long maxBytesPerRun,
            int maxConcurrentOperations,
            Instant updatedAt) {}

    public record PolicyRequest(
            @NotBlank String name,
            String description,
            boolean enabled,
            @NotNull MaintenancePolicy.Selector selector,
            @NotBlank String cron,
            @NotBlank String timezone,
            @NotNull MaintenancePolicy.MaintenanceWindow maintenanceWindow,
            @NotNull Set<OperationType> operations,
            @NotNull Map<OperationType, MaintenancePolicy.OperationConfig> operationConfigs,
            @Min(0) long maxBytesPerRun,
            @Min(1) int maxConcurrentOperations,
            boolean requireApprovalAboveBudget) {}

    public record PolicyValidation(boolean valid, List<String> errors, List<String> warnings) {}

    public record PolicySimulationMatch(
            UUID tableId,
            String qualifiedName,
            TableAccessMode accessMode,
            Set<OperationType> availableOperations,
            Map<OperationType, String> blockedOperations) {}

    public record PolicySimulation(int matchedTableCount, List<PolicySimulationMatch> tables) {}

    public record PlanRequest(
            @NotNull UUID tableId,
            @NotNull UUID policyId,
            @NotNull OperationType operationType,
            @NotBlank String idempotencyKey) {}

    public record OperationView(
            UUID id,
            UUID workspaceId,
            UUID tableId,
            String tableName,
            ExecutionTarget executionTarget,
            UUID policyId,
            OperationType operationType,
            OperationState state,
            long basedOnVersion,
            long estimatedBytes,
            boolean approvalRequired,
            String approvedBy,
            String externalJobId,
            String commandPreview,
            List<String> reasons,
            List<String> warnings,
            Map<String, Object> policyEvaluation,
            Map<String, Object> preflightEvidence,
            Map<String, Object> result,
            Map<String, Object> verificationEvidence,
            List<Map<String, Object>> steps,
            boolean maintenanceApplied,
            String structuredResultUri,
            String structuredResultChecksum,
            String errorCode,
            String errorMessage,
            Instant plannedAt,
            Instant startedAt,
            Instant completedAt) {}

    public record ApprovalRequest(@NotBlank String decision, String comment) {}

    public record ConnectionView(
            UUID id,
            UUID environmentId,
            String name,
            String catalogType,
            String catalogUri,
            String warehouseUri,
            String engineType,
            String engineUri,
            String secretRef,
            Map<String, String> options,
            boolean enabled,
            Instant lastTestedAt,
            String lastTestStatus,
            String lastTestMessage) {}

    public record ConnectionRequest(
            @NotNull UUID environmentId,
            @NotBlank String name,
            @NotBlank String catalogType,
            String catalogUri,
            String warehouseUri,
            @NotBlank String engineType,
            @NotBlank String engineUri,
            String secretRef,
            Map<String, String> options) {}

    public record DiscoveryRequest(
            String rootUri,
            @Min(0) @Max(32) Integer maxDepth,
            @Min(1) @Max(100000) Integer maxTables,
            @Min(1) @Max(86400) Integer timeoutSeconds) {}

    public record DiscoveryRunView(
            UUID id,
            UUID connectionId,
            String state,
            String rootUri,
            int maxDepth,
            int maxTables,
            int timeoutSeconds,
            int tablesFound,
            int tablesMissing,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {}

    public record ConnectionTestResult(
            boolean reachable, String status, String message, Map<String, Object> capabilities) {}

    public record ApiKeyRequest(
            @NotBlank String name, @NotNull Set<io.fiq.domain.Role> roles, Instant expiresAt) {}

    public record ApiKeyView(
            UUID id,
            String name,
            String keyPrefix,
            Set<io.fiq.domain.Role> roles,
            boolean enabled,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt) {}

    public record CreatedApiKey(ApiKeyView apiKey, String secret) {}

    public record AuditEvent(
            long id,
            Instant occurredAt,
            String eventType,
            String severity,
            String principal,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {}

    public record ListQuery(
            String cursor,
            @Min(1) @Max(200) int limit,
            String search,
            String environment,
            String status) {}

    private ApiModels() {}
}
