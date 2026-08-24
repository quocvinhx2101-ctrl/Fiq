package io.fiq.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PolicyEvaluation(
        OperationType operationType,
        long observedVersion,
        Instant evaluatedAt,
        PolicyDecision decision,
        boolean approvalRequired,
        Map<String, Object> observations,
        Map<String, Object> policyThresholds,
        List<PolicyCondition> conditions,
        List<PolicyBlocker> blockers) {
    public PolicyEvaluation {
        operationType = Objects.requireNonNull(operationType, "operationType");
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        decision = Objects.requireNonNull(decision, "decision");
        observations = Map.copyOf(observations);
        policyThresholds = Map.copyOf(policyThresholds);
        conditions = List.copyOf(conditions);
        blockers = List.copyOf(blockers);
        if (decision == PolicyDecision.BLOCKED && blockers.isEmpty()) {
            throw new IllegalArgumentException("BLOCKED evaluation requires blockers");
        }
        if (decision == PolicyDecision.NOT_ELIGIBLE
                && conditions.stream().allMatch(PolicyCondition::passed)) {
            throw new IllegalArgumentException(
                    "NOT_ELIGIBLE evaluation requires a failed condition");
        }
    }
}
