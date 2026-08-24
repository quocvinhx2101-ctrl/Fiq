package io.fiq.domain;

import java.util.Objects;

public record PolicyCondition(
        String metric,
        Object observedValue,
        String operator,
        Object policyValue,
        boolean passed,
        String reason) {
    public PolicyCondition {
        metric = Objects.requireNonNull(metric, "metric");
        observedValue = Objects.requireNonNull(observedValue, "observedValue");
        operator = Objects.requireNonNull(operator, "operator");
        policyValue = Objects.requireNonNull(policyValue, "policyValue");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
