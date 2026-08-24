package io.fiq.engine.spark;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fiq.domain.CatalogTarget;
import io.fiq.domain.OperationType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SparkMaintenanceRequestTest {
    @Test
    void rejectsSqlControlCharactersInPolicyExpressions() {
        assertThatThrownBy(
                        () ->
                                new SparkMaintenanceRequest(
                                        UUID.randomUUID(),
                                        OperationType.OPTIMIZE_BINPACK,
                                        new CatalogTarget("main", List.of("analytics"), "events"),
                                        1,
                                        168,
                                        List.of(),
                                        "day = '2026-08-24'; DROP TABLE x",
                                        "",
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL control characters");
    }

    @Test
    void rejectsUnsafeTableIdentifiers() {
        assertThatThrownBy(
                        () ->
                                new SparkMaintenanceRequest(
                                        UUID.randomUUID(),
                                        OperationType.OPTIMIZE_BINPACK,
                                        new CatalogTarget(
                                                "main", List.of("analytics"), "events\nDROP"),
                                        1,
                                        168,
                                        List.of(),
                                        "",
                                        "",
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }
}
