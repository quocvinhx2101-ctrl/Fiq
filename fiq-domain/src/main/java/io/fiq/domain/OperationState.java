package io.fiq.domain;

import java.util.EnumSet;
import java.util.Set;

public enum OperationState {
    PLANNED,
    AWAITING_APPROVAL,
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
    }

    public boolean canTransitionTo(OperationState target) {
        if (target == null || isTerminal()) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    private Set<OperationState> allowedTargets() {
        return switch (this) {
            case PLANNED -> EnumSet.of(AWAITING_APPROVAL, QUEUED, SKIPPED);
            case AWAITING_APPROVAL -> EnumSet.of(QUEUED, CANCELLED, SKIPPED);
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED, SKIPPED);
            case RUNNING -> EnumSet.of(CANCELLING, SUCCEEDED, FAILED, CANCELLED);
            case CANCELLING -> EnumSet.of(CANCELLED, FAILED);
            case SUCCEEDED, FAILED, CANCELLED, SKIPPED -> EnumSet.noneOf(OperationState.class);
        };
    }
}
