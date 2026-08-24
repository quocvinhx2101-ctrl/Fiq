package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaintenancePlannerTest {
    private final MaintenancePlanner planner =
            new MaintenancePlanner(
                    new DeltaCapabilities(),
                    Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void plansAnExecutableBinPackForMeaningfulSmallFileDebt() {
        var config = new MaintenancePolicy.OperationConfig(168, List.of(), "", "", true, false);

        var plan =
                planner.plan(
                        DomainFixtures.snapshot(Set.of("deletionVectors"), List.of()),
                        DomainFixtures.health(true),
                        DomainFixtures.policy(OperationType.OPTIMIZE_BINPACK, config),
                        OperationType.OPTIMIZE_BINPACK);

        assertThat(plan.executable()).isTrue();
        assertThat(plan.commandPreview()).isEqualTo("OPTIMIZE `main`.`analytics`.`events`");
        assertThat(plan.estimatedBytes()).isEqualTo(10_000_000_000L);
    }

    @Test
    void vacuumLiteFailsClosedWhenCoverageIsMissing() {
        var config = new MaintenancePolicy.OperationConfig(168, List.of(), "", "", true, false);

        var plan =
                planner.plan(
                        DomainFixtures.snapshot(Set.of(), List.of()),
                        DomainFixtures.health(false),
                        DomainFixtures.policy(OperationType.VACUUM_LITE, config),
                        OperationType.VACUUM_LITE);

        assertThat(plan.executable()).isFalse();
        assertThat(plan.reasons())
                .containsExactly(
                        "VACUUM LITE requires transaction-log coverage for the retention window");
        assertThat(plan.warnings()).contains("FIQ will execute VACUUM DRY RUN before deletion");
    }

    @Test
    void unsafeRetentionRequiresApproval() {
        var config = new MaintenancePolicy.OperationConfig(24, List.of(), "", "", true, false);

        var plan =
                planner.plan(
                        DomainFixtures.snapshot(Set.of(), List.of()),
                        DomainFixtures.health(true),
                        DomainFixtures.policy(OperationType.VACUUM_FULL, config),
                        OperationType.VACUUM_FULL);

        assertThat(plan.executable()).isTrue();
        assertThat(plan.approvalRequired()).isTrue();
        assertThat(plan.warnings()).contains("Retention is below the safe 168-hour default");
    }
}
