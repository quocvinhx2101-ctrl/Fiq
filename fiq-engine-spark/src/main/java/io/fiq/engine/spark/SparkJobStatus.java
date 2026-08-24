package io.fiq.engine.spark;

import java.util.List;
import java.util.Map;

public record SparkJobStatus(
        String jobId,
        SparkJobState state,
        List<String> log,
        Map<String, Object> evidence,
        String errorMessage) {
    public SparkJobStatus {
        log = List.copyOf(log == null ? List.of() : log);
        evidence = Map.copyOf(evidence == null ? Map.of() : evidence);
    }
}
