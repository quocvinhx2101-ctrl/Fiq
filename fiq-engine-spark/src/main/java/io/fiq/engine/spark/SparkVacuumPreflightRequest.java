package io.fiq.engine.spark;

import io.fiq.domain.ExecutionTarget;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SparkVacuumPreflightRequest(
        UUID preflightId,
        ExecutionTarget executionTarget,
        long expectedVersion,
        long retentionHours,
        String resultPrefix,
        boolean allowUnsafeRetention,
        Map<String, String> sparkConf) {
    public SparkVacuumPreflightRequest {
        preflightId = Objects.requireNonNull(preflightId, "preflightId");
        executionTarget = Objects.requireNonNull(executionTarget, "executionTarget");
        resultPrefix = Objects.requireNonNull(resultPrefix, "resultPrefix");
        sparkConf = Map.copyOf(Objects.requireNonNullElse(sparkConf, Map.of()));
        if (expectedVersion < 0 || retentionHours < 0) {
            throw new IllegalArgumentException("VACUUM preflight values must be non-negative");
        }
    }
}
