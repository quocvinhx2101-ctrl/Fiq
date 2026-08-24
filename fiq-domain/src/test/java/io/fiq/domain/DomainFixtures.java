package io.fiq.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
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
        var assessedAt = Instant.parse("2026-08-24T00:00:00Z");
        var dimensions =
                new EnumMap<HealthDimension, HealthDimensionMetadata>(HealthDimension.class);
        for (var dimension : HealthDimension.values()) {
            dimensions.put(
                    dimension,
                    new HealthDimensionMetadata(
                            dimension,
                            HealthCompleteness.COMPLETE,
                            "test",
                            assessedAt,
                            42,
                            Optional.empty()));
        }
        var buckets = new ArrayList<Long>(java.util.Collections.nCopies(1024, 0L));
        buckets.set(4, 40L);
        buckets.set(256, 60L);
        return new HealthAssessment(
                TABLE,
                42,
                assessedAt,
                dimensions,
                new HealthAssessment.FileLayout(
                        100,
                        10_000_000_000L,
                        1,
                        500_000_000,
                        100_000_000,
                        new HealthAssessment.QuantileEstimate(
                                256 * FileSizeHistogram.MIB, FileSizeHistogram.MIB / 2, "test"),
                        new FileSizeHistogram(FileSizeHistogram.MIB, buckets, 0),
                        3,
                        1.2),
                new HealthAssessment.DeletionVectors(10, 1_000_000, 1000L, 0.1),
                new HealthAssessment.TransactionLog(
                        42, 40L, assessedAt, "V2", 2L, 10L, 1000L, logCoverage),
                new HealthAssessment.StorageRetention(
                        20L, 2_000_000_000L, assessedAt, assessedAt, List.of(), List.of()),
                new HealthAssessment.Clustering(
                        List.of("event_date"), false, List.of(), null, true, true, false),
                new HealthAssessment.Protocol(
                        3,
                        7,
                        List.of("deletionVectors"),
                        TableAccessMode.CLASSIC,
                        "4.0.1",
                        "4.0.1",
                        true,
                        true,
                        true),
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
