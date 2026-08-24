package io.fiq.engine.spark;

import io.fiq.domain.ExecutionTarget;
import io.fiq.domain.OperationType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SparkMaintenanceRequest(
        UUID operationId,
        OperationType operationType,
        ExecutionTarget executionTarget,
        long expectedVersion,
        long retentionHours,
        List<String> zOrderColumns,
        String predicate,
        String inventoryTable,
        String resultPrefix,
        String approvedCandidateHash,
        long approvedCandidateCount,
        boolean allowUnsafeRetention,
        Map<String, String> sparkConf) {
    public SparkMaintenanceRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        operationType = Objects.requireNonNull(operationType, "operationType");
        executionTarget = Objects.requireNonNull(executionTarget, "executionTarget");
        zOrderColumns = List.copyOf(zOrderColumns == null ? List.of() : zOrderColumns);
        zOrderColumns.forEach(SparkMaintenanceRequest::requireSafeIdentifier);
        predicate = Objects.requireNonNullElse(predicate, "");
        inventoryTable = Objects.requireNonNullElse(inventoryTable, "");
        resultPrefix = Objects.requireNonNullElse(resultPrefix, "");
        approvedCandidateHash = Objects.requireNonNullElse(approvedCandidateHash, "");
        sparkConf = Map.copyOf(sparkConf == null ? Map.of() : sparkConf);
        if (retentionHours < 0) throw new IllegalArgumentException("retentionHours must be >= 0");
        if (approvedCandidateCount < 0) {
            throw new IllegalArgumentException("approvedCandidateCount must be >= 0");
        }
        rejectSqlControlCharacters(predicate, "predicate");
        rejectSqlControlCharacters(inventoryTable, "inventoryTable");
    }

    public SparkMaintenanceRequest(
            UUID operationId,
            OperationType operationType,
            ExecutionTarget executionTarget,
            long expectedVersion,
            long retentionHours,
            List<String> zOrderColumns,
            String predicate,
            String inventoryTable,
            Map<String, String> sparkConf) {
        this(
                operationId,
                operationType,
                executionTarget,
                expectedVersion,
                retentionHours,
                zOrderColumns,
                predicate,
                inventoryTable,
                "",
                "",
                0,
                false,
                sparkConf);
    }

    private static void requireSafeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + value);
        }
    }

    private static void rejectSqlControlCharacters(String value, String field) {
        var normalized = value.toLowerCase();
        if (value.contains(";") || normalized.contains("--") || normalized.contains("/*")) {
            throw new IllegalArgumentException(field + " contains SQL control characters");
        }
    }
}
