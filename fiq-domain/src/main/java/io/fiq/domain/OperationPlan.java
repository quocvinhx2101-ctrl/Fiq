package io.fiq.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OperationPlan(
        UUID id,
        TableIdentifier table,
        OperationType operationType,
        long basedOnVersion,
        Instant createdAt,
        long estimatedBytes,
        boolean executable,
        boolean approvalRequired,
        List<String> reasons,
        List<String> warnings,
        PolicyEvaluation evaluation,
        String commandPreview) {
    public OperationPlan {
        id = Objects.requireNonNull(id, "id");
        table = Objects.requireNonNull(table, "table");
        operationType = Objects.requireNonNull(operationType, "operationType");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        reasons = List.copyOf(reasons);
        warnings = List.copyOf(warnings);
        evaluation = Objects.requireNonNull(evaluation, "evaluation");
        commandPreview = Objects.requireNonNullElse(commandPreview, "");
        if (estimatedBytes < 0) throw new IllegalArgumentException("estimatedBytes must be >= 0");
        if (!executable && reasons.isEmpty()) {
            throw new IllegalArgumentException("non-executable plans require a reason");
        }
    }
}
