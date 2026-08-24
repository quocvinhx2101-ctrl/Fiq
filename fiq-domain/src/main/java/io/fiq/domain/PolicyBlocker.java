package io.fiq.domain;

import java.util.Objects;

public record PolicyBlocker(String code, HealthDimension dimension, String reason) {
    public PolicyBlocker {
        code = Objects.requireNonNull(code, "code");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
