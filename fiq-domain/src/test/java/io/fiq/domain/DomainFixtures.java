package io.fiq.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class DomainFixtures {
    static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final TableIdentifier TABLE =
            new TableIdentifier(
                    WORKSPACE,
                    "production",
                    "main",
                    List.of("analytics"),
                    "events",
                    Optional.of("s3://lake/analytics/events"),
                    PathTarget.of("s3://lake/analytics/events"));

    static DeltaTableSnapshot snapshot(Set<String> features, List<String> clusteringColumns) {
        return new DeltaTableSnapshot(
                TABLE,
                TableAccessMode.CLASSIC,
                42,
                Instant.parse("2026-08-24T00:00:00Z"),
                3,
                7,
                features,
                List.of("event_date"),
                clusteringColumns,
                Map.of(),
                true,
                true);
    }

    static HealthAssessment health(boolean logCoverage) {
        return new HealthAssessment(
                TABLE,
                42,
                Instant.parse("2026-08-24T00:00:00Z"),
                "delta-kernel-4.0.1",
                HealthCompleteness.COMPLETE,
                Optional.empty(),
                new HealthAssessment.FileLayout(
                        100,
                        10_000_000_000L,
                        1,
                        500_000_000,
                        50_000_000,
                        100_000_000,
                        40,
                        0.4,
                        3,
                        1.2),
                new HealthAssessment.DeletionVectors(10, 1_000_000, 1000, 0.1),
                new HealthAssessment.TransactionLog(
                        42, 2, Optional.empty(), "V2", 10, 1000, logCoverage, 0),
                new HealthAssessment.StorageRetention(20, 2_000_000_000L, 168),
                new HealthAssessment.Clustering(
                        List.of("event_date"), List.of(), Optional.empty(), 0.0),
                new HealthAssessment.Protocol(
                        3, 7, List.of("deletionVectors"), TableAccessMode.CLASSIC, true),
                List.of());
    }

    static MaintenancePolicy policy(OperationType type, MaintenancePolicy.OperationConfig config) {
        return new MaintenancePolicy(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                WORKSPACE,
                "default",
                true,
                new MaintenancePolicy.Selector("*", "*", "*", "*", Map.of()),
                "0 0 2 * * ?",
                ZoneId.of("UTC"),
                new MaintenancePolicy.MaintenanceWindow(
                        EnumSet.allOf(DayOfWeek.class), LocalTime.MIN, LocalTime.MAX),
                Set.of(type),
                Map.of(type, config),
                20_000_000_000L,
                2,
                true);
    }

    private DomainFixtures() {}
}
