package io.fiq.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable observations. Maintenance thresholds deliberately do not belong in this type. */
public record HealthAssessment(
        TableIdentifier table,
        long observedVersion,
        Instant assessedAt,
        Map<HealthDimension, HealthDimensionMetadata> dimensions,
        FileLayout fileLayout,
        DeletionVectors deletionVectors,
        TransactionLog transactionLog,
        StorageRetention storageRetention,
        Clustering clustering,
        Protocol protocol,
        List<HealthIssue> issues) {

    public HealthAssessment {
        table = Objects.requireNonNull(table, "table");
        assessedAt = Objects.requireNonNull(assessedAt, "assessedAt");
        if (observedVersion < 0) throw new IllegalArgumentException("observedVersion is negative");
        var copy = new EnumMap<HealthDimension, HealthDimensionMetadata>(HealthDimension.class);
        copy.putAll(Objects.requireNonNull(dimensions, "dimensions"));
        for (var dimension : HealthDimension.values()) {
            var metadata = copy.get(dimension);
            if (metadata == null || metadata.dimension() != dimension) {
                throw new IllegalArgumentException("Missing metadata for " + dimension);
            }
            if (metadata.observedVersion() != observedVersion) {
                throw new IllegalArgumentException(
                        "Dimension version differs from assessment version");
            }
        }
        dimensions = Map.copyOf(copy);
        fileLayout = Objects.requireNonNull(fileLayout, "fileLayout");
        deletionVectors = Objects.requireNonNull(deletionVectors, "deletionVectors");
        transactionLog = Objects.requireNonNull(transactionLog, "transactionLog");
        storageRetention = Objects.requireNonNull(storageRetention, "storageRetention");
        clustering = Objects.requireNonNull(clustering, "clustering");
        protocol = Objects.requireNonNull(protocol, "protocol");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public HealthCompleteness completeness() {
        if (dimensions.values().stream()
                .anyMatch(value -> value.completeness() == HealthCompleteness.STALE)) {
            return HealthCompleteness.STALE;
        }
        return dimensions.values().stream()
                        .allMatch(value -> value.completeness() == HealthCompleteness.COMPLETE)
                ? HealthCompleteness.COMPLETE
                : HealthCompleteness.PARTIAL;
    }

    public String provenance() {
        return dimensions.values().stream()
                .map(HealthDimensionMetadata::provenance)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("unknown");
    }

    public Optional<String> staleReason() {
        return dimensions.values().stream()
                .filter(value -> value.completeness() != HealthCompleteness.COMPLETE)
                .flatMap(value -> value.incompleteReason().stream())
                .distinct()
                .reduce((left, right) -> left + "; " + right);
    }

    public record QuantileEstimate(long valueBytes, long errorBoundBytes, String method) {
        public QuantileEstimate {
            if (valueBytes < 0 || errorBoundBytes < 0) {
                throw new IllegalArgumentException("quantile values must be non-negative");
            }
            method = Objects.requireNonNull(method, "method");
        }
    }

    public record FileLayout(
            long activeFileCount,
            long totalBytes,
            long minimumFileBytes,
            long maximumFileBytes,
            double averageFileBytes,
            QuantileEstimate medianFileBytes,
            FileSizeHistogram fileSizeHistogram,
            long partitionCount,
            double partitionSkew) {
        public FileLayout {
            medianFileBytes = Objects.requireNonNull(medianFileBytes, "medianFileBytes");
            fileSizeHistogram = Objects.requireNonNull(fileSizeHistogram, "fileSizeHistogram");
            if (fileSizeHistogram.totalCount() != activeFileCount) {
                throw new IllegalArgumentException("histogram count differs from activeFileCount");
            }
        }
    }

    public record DeletionVectors(
            long filesWithDeletionVectors,
            long deletionVectorBytes,
            Long deletedRows,
            Double deletedRowRatio) {}

    public record TransactionLog(
            long currentVersion,
            Long checkpointVersion,
            Instant checkpointAt,
            String checkpointType,
            Long commitsSinceCheckpoint,
            Long relevantLogFileCount,
            Long relevantLogBytes,
            Boolean logCoverageSufficient) {}

    public record StorageRetention(
            Long tombstoneCount,
            Long tombstoneBytes,
            Instant oldestDeletionTimestamp,
            Instant newestDeletionTimestamp,
            List<Long> tombstoneAgeBucketHours,
            List<Long> tombstoneAgeBucketCounts) {
        public StorageRetention {
            tombstoneAgeBucketHours = List.copyOf(tombstoneAgeBucketHours);
            tombstoneAgeBucketCounts = List.copyOf(tombstoneAgeBucketCounts);
            if (tombstoneAgeBucketCounts.size() != tombstoneAgeBucketHours.size() + 1
                    && !tombstoneAgeBucketCounts.isEmpty()) {
                throw new IllegalArgumentException("tombstone age histogram is invalid");
            }
        }
    }

    public record Clustering(
            List<String> partitionColumns,
            boolean liquidClusteringEnabled,
            List<String> clusteringColumns,
            Instant domainUpdatedAt,
            boolean zOrderEligible,
            boolean optimizeEligible,
            boolean optimizeFullEligible) {
        public Clustering {
            partitionColumns = List.copyOf(partitionColumns);
            clusteringColumns = List.copyOf(clusteringColumns);
        }
    }

    public record Protocol(
            int minReaderVersion,
            int minWriterVersion,
            List<String> tableFeatures,
            TableAccessMode accessMode,
            String sparkRuntimeVersion,
            String deltaRuntimeVersion,
            boolean readQualified,
            boolean mutationQualified,
            boolean filesystemVisibleStateCurrent) {
        public Protocol {
            tableFeatures = List.copyOf(tableFeatures);
        }
    }
}
