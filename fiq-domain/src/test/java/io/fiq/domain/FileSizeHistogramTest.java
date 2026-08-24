package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class FileSizeHistogramTest {
    @Test
    void derivesExactCountsForWholeMibPolicyThresholds() {
        var buckets = new ArrayList<Long>(Collections.nCopies(1024, 0L));
        buckets.set(0, 4L);
        buckets.set(1, 3L);
        buckets.set(127, 8L);
        buckets.set(128, 20L);
        var value = new FileSizeHistogram(FileSizeHistogram.MIB, buckets, 2);

        assertThat(value.countBelow(128 * FileSizeHistogram.MIB)).isEqualTo(15);
        assertThat(value.totalCount()).isEqualTo(37);
    }

    @Test
    void rejectsThresholdsThatCannotBeAnsweredExactly() {
        var buckets = new ArrayList<Long>(Collections.nCopies(1024, 0L));
        var value = new FileSizeHistogram(FileSizeHistogram.MIB, buckets, 0);

        assertThatThrownBy(() -> value.countBelow(FileSizeHistogram.MIB + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole MiB");
    }
}
