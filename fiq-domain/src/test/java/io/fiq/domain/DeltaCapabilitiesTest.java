package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeltaCapabilitiesTest {
    private final DeltaCapabilities capabilities = new DeltaCapabilities();

    @Test
    void unknownFeaturesForceReadOnlyMode() {
        var result =
                capabilities.evaluate(DomainFixtures.snapshot(Set.of("futureFeature"), List.of()));

        assertThat(result.values()).allMatch(capability -> !capability.supported());
        assertThat(result.get(OperationType.OPTIMIZE_BINPACK).reason()).contains("futureFeature");
    }

    @Test
    void onlyBinPackAndVacuumFullAreMutationQualifiedInPhaseOne() {
        var result =
                capabilities.evaluate(
                        DomainFixtures.snapshot(Set.of("clustering"), List.of("customer_id")));

        assertThat(result.get(OperationType.OPTIMIZE_ZORDER).supported()).isFalse();
        assertThat(result.get(OperationType.OPTIMIZE_CLUSTERING).supported()).isFalse();
        assertThat(result.get(OperationType.OPTIMIZE_FULL).supported()).isFalse();
        assertThat(result.get(OperationType.VACUUM_LITE).supported()).isFalse();
        assertThat(result.get(OperationType.OPTIMIZE_BINPACK).supported()).isTrue();
        assertThat(result.get(OperationType.VACUUM_FULL).supported()).isTrue();
    }

    @Test
    void catalogManagedVacuumIsNeverBypassedOnFourZeroOne() {
        var classic = DomainFixtures.snapshot(Set.of("catalogManaged"), List.of());
        var managed =
                new DeltaTableSnapshot(
                        classic.table(),
                        TableAccessMode.CATALOG_MANAGED,
                        classic.version(),
                        Instant.now(),
                        3,
                        7,
                        classic.tableFeatures(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        true,
                        false);

        var result = capabilities.evaluate(managed);

        assertThat(result.get(OperationType.VACUUM_LITE).supported()).isFalse();
        assertThat(result.get(OperationType.VACUUM_FULL).reason()).contains("4.0.1");
    }
}
