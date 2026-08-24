package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MaintenancePolicyTest {
    @Test
    void validatesFileThresholdAgainstIndependentHistogramBoundaries() {
        assertThatThrownBy(
                        () ->
                                new MaintenancePolicy.FileLayoutPolicy(
                                        128 * FileSizeHistogram.MIB + 1,
                                        20,
                                        0.3,
                                        0,
                                        1024 * FileSizeHistogram.MIB,
                                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole MiB");
    }
}
