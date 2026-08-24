package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
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
        assertThat(plan.commandPreview()).isEqualTo("OPTIMIZE delta.`s3://lake/analytics/events`");
        assertThat(plan.estimatedBytes()).isEqualTo(188_743_680L);
        assertThat(plan.evaluation().decision()).isEqualTo(PolicyDecision.ELIGIBLE);
        assertThat(plan.evaluation().observations())
                .containsEntry("filesBelowThreshold", 40L)
                .containsEntry("smallFileRatio", 0.4);
    }

    @Test
    void vacuumLiteIsNotQualifiedInPhaseOne() {
        var config = new MaintenancePolicy.OperationConfig(168, List.of(), "", "", true, false);

        var plan =
                planner.plan(
                        DomainFixtures.snapshot(Set.of(), List.of()),
                        DomainFixtures.health(false),
                        DomainFixtures.policy(OperationType.VACUUM_LITE, config),
                        OperationType.VACUUM_LITE);

        assertThat(plan.executable()).isFalse();
        assertThat(plan.reasons()).contains("Operation is not qualified in FIQ Phase 1");
        assertThat(plan.evaluation().decision()).isEqualTo(PolicyDecision.BLOCKED);
    }

    @Test
    void unsafeRetentionIsBlockedOutsideAnIsolatedSample() {
        var config = new MaintenancePolicy.OperationConfig(24, List.of(), "", "", true, false);

        var plan =
                planner.plan(
                        DomainFixtures.snapshot(Set.of(), List.of()),
                        DomainFixtures.health(true),
                        DomainFixtures.policy(OperationType.VACUUM_FULL, config),
                        OperationType.VACUUM_FULL,
                        new VacuumPreflightEvidence(
                                42,
                                10,
                                2_000_000_000L,
                                "sha256:candidates",
                                24,
                                Instant.parse("2026-08-24T00:30:00Z")));

        assertThat(plan.executable()).isFalse();
        assertThat(plan.evaluation().decision()).isEqualTo(PolicyDecision.BLOCKED);
        assertThat(plan.reasons())
                .contains(
                        "Retention below 168 hours is qualified only for isolated zero-hour sample tables");
        assertThat(plan.warnings()).contains("Retention is below the safe 168-hour default");
    }

    @Test
    void sameAssessmentProducesDifferentExplainablePolicyDecisions() {
        var permissive = optimizeConfig(20, 0.30);
        var strict = optimizeConfig(80, 0.75);
        var snapshot = DomainFixtures.snapshot(Set.of(), List.of());
        var assessment = DomainFixtures.health(true);

        var eligible =
                planner.evaluate(
                        snapshot,
                        assessment,
                        DomainFixtures.policy(OperationType.OPTIMIZE_BINPACK, permissive),
                        OperationType.OPTIMIZE_BINPACK);
        var ineligible =
                planner.evaluate(
                        snapshot,
                        assessment,
                        DomainFixtures.policy(OperationType.OPTIMIZE_BINPACK, strict),
                        OperationType.OPTIMIZE_BINPACK);

        assertThat(eligible.decision()).isEqualTo(PolicyDecision.ELIGIBLE);
        assertThat(ineligible.decision()).isEqualTo(PolicyDecision.NOT_ELIGIBLE);
        assertThat(ineligible.conditions()).anyMatch(value -> !value.passed());
    }

    @Test
    void incompleteClusteringDoesNotBlockBinPacking() {
        var original = DomainFixtures.health(true);
        var dimensions =
                new EnumMap<HealthDimension, HealthDimensionMetadata>(original.dimensions());
        dimensions.put(
                HealthDimension.CLUSTERING,
                new HealthDimensionMetadata(
                        HealthDimension.CLUSTERING,
                        HealthCompleteness.PARTIAL,
                        "test",
                        original.assessedAt(),
                        original.observedVersion(),
                        Optional.of("domain freshness unknown")));
        var assessment =
                new HealthAssessment(
                        original.table(),
                        original.observedVersion(),
                        original.assessedAt(),
                        dimensions,
                        original.fileLayout(),
                        original.deletionVectors(),
                        original.transactionLog(),
                        original.storageRetention(),
                        original.clustering(),
                        original.protocol(),
                        original.issues());

        var evaluation =
                planner.evaluate(
                        DomainFixtures.snapshot(Set.of(), List.of()),
                        assessment,
                        DomainFixtures.policy(
                                OperationType.OPTIMIZE_BINPACK, optimizeConfig(20, 0.3)),
                        OperationType.OPTIMIZE_BINPACK);

        assertThat(evaluation.decision()).isEqualTo(PolicyDecision.ELIGIBLE);
    }

    private static MaintenancePolicy.OperationConfig optimizeConfig(
            long minimumFiles, double minimumRatio) {
        return new MaintenancePolicy.OperationConfig(
                168,
                List.of(),
                "",
                "",
                false,
                false,
                new MaintenancePolicy.FileLayoutPolicy(
                        128 * FileSizeHistogram.MIB,
                        minimumFiles,
                        minimumRatio,
                        0,
                        1024 * FileSizeHistogram.MIB,
                        0),
                MaintenancePolicy.VacuumPolicy.defaultTemplate());
    }
}
