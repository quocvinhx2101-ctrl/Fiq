package io.fiq.domain;

import java.util.Objects;

public record HealthIssue(
        String code,
        HealthSeverity severity,
        String dimension,
        String summary,
        String recommendation) {
    public HealthIssue {
        code = requireText(code, "code");
        severity = Objects.requireNonNull(severity, "severity");
        dimension = requireText(dimension, "dimension");
        summary = requireText(summary, "summary");
        recommendation = Objects.requireNonNullElse(recommendation, "");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
