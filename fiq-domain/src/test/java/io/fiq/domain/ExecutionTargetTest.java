package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionTargetTest {
    @Test
    void canonicalizesAndRendersPathTargetsWithoutConvertingThemToNames() {
        var target = PathTarget.of("S3A://BUCKET/a/../tables/events/");

        assertThat(target.uri().toString()).isEqualTo("s3a://bucket/tables/events");
        assertThat(ExecutionTargetRenderer.sql(target))
                .isEqualTo("delta.`s3a://bucket/tables/events`");
    }

    @Test
    void rendersCatalogTargetsAsQuotedIdentifiers() {
        var target = new CatalogTarget("spark_catalog", List.of("analytics"), "events");

        assertThat(ExecutionTargetRenderer.sql(target))
                .isEqualTo("`spark_catalog`.`analytics`.`events`");
    }

    @Test
    void rejectsCredentialsEmbeddedInPathTargets() {
        assertThatThrownBy(() -> PathTarget.of("s3a://user:secret@bucket/table"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }
}
