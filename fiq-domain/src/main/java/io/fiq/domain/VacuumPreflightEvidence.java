package io.fiq.domain;

import java.time.Instant;
import java.util.Objects;

public record VacuumPreflightEvidence(
        long plannedVersion,
        long candidateCount,
        Long candidateBytes,
        String candidateHash,
        long retentionHours,
        Instant observedAt) {
    public VacuumPreflightEvidence {
        if (plannedVersion < 0 || candidateCount < 0 || retentionHours < 0) {
            throw new IllegalArgumentException("VACUUM preflight values must be non-negative");
        }
        if (candidateBytes != null && candidateBytes < 0) {
            throw new IllegalArgumentException("candidateBytes must be non-negative when known");
        }
        candidateHash = Objects.requireNonNull(candidateHash, "candidateHash");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
