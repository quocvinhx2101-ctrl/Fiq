package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssessmentFactsTest {
    @Test
    void onePolicyIndependentHistogramAnswersDifferentPolicyThresholds() {
        var assessment = DomainFixtures.health(true);
        var histogram = assessment.fileLayout().fileSizeHistogram();

        assertThat(histogram.countBelow(8 * FileSizeHistogram.MIB)).isEqualTo(40);
        assertThat(histogram.countBelow(128 * FileSizeHistogram.MIB)).isEqualTo(40);
        assertThat(histogram.countBelow(512 * FileSizeHistogram.MIB)).isEqualTo(100);
    }

    @Test
    void unknownRatiosRemainNullInsteadOfBecomingZero() {
        var facts = new HealthAssessment.DeletionVectors(3, 2048, 12L, null);

        assertThat(facts.deletedRows()).isEqualTo(12);
        assertThat(facts.deletedRowRatio()).isNull();
    }
}
