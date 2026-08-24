package io.fiq.engine.spark;

import io.fiq.domain.ExecutionTarget;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SparkAssessmentRequest(
        UUID assessmentId, ExecutionTarget executionTarget, Map<String, String> sparkConf) {
    public SparkAssessmentRequest {
        assessmentId = Objects.requireNonNull(assessmentId, "assessmentId");
        executionTarget = Objects.requireNonNull(executionTarget, "executionTarget");
        sparkConf = Map.copyOf(Objects.requireNonNullElse(sparkConf, Map.of()));
    }
}
