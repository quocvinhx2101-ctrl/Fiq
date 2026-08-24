package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VacuumCandidateIdentityTest {
    @Test
    void canonicalIdentityDoesNotDependOnSparkOutputOrdering() {
        var first =
                VacuumCandidateIdentity.fromUris(
                        List.of(
                                "s3a://lake/table/part-2.parquet",
                                "s3a://lake/table/staging/../part-1.parquet"));
        var second =
                VacuumCandidateIdentity.fromUris(
                        List.of(
                                "s3a://lake/table/part-1.parquet",
                                "s3a://lake/table/part-2.parquet",
                                "s3a://lake/table/part-2.parquet"));

        assertThat(first.canonicalUris()).containsExactlyElementsOf(second.canonicalUris());
        assertThat(first.sha256()).isEqualTo(second.sha256());
    }
}
