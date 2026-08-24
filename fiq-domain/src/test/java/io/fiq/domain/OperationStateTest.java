package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperationStateTest {
    @Test
    void enforcesTheLifecycle() {
        assertThat(OperationState.PLANNED.canTransitionTo(OperationState.AWAITING_APPROVAL))
                .isTrue();
        assertThat(OperationState.AWAITING_APPROVAL.canTransitionTo(OperationState.QUEUED))
                .isTrue();
        assertThat(OperationState.QUEUED.canTransitionTo(OperationState.RUNNING)).isTrue();
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.SUCCEEDED)).isTrue();
        assertThat(OperationState.RUNNING.canTransitionTo(OperationState.CANCELLED)).isTrue();
        assertThat(OperationState.SUCCEEDED.canTransitionTo(OperationState.RUNNING)).isFalse();
    }
}
