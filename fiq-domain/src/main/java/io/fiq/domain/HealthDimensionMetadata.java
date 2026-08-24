package io.fiq.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record HealthDimensionMetadata(
        HealthDimension dimension,
        HealthCompleteness completeness,
        String provenance,
        Instant observedAt,
        long observedVersion,
        Optional<String> incompleteReason) {
    public HealthDimensionMetadata {
        dimension = Objects.requireNonNull(dimension, "dimension");
        completeness = Objects.requireNonNull(completeness, "completeness");
        provenance = Objects.requireNonNull(provenance, "provenance");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        incompleteReason = incompleteReason == null ? Optional.empty() : incompleteReason;
        if (observedVersion < 0) throw new IllegalArgumentException("observedVersion is negative");
    }
}
