package io.fiq.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record HealthAssessment(
        TableIdentifier table,
        long observedVersion,
        Instant assessedAt,
        String provenance,
        HealthCompleteness completeness,
        Optional<String> staleReason,
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
        provenance = Objects.requireNonNull(provenance, "provenance");
        completeness = Objects.requireNonNull(completeness, "completeness");
        staleReason = staleReason == null ? Optional.empty() : staleReason;
        fileLayout = Objects.requireNonNull(fileLayout, "fileLayout");
        deletionVectors = Objects.requireNonNull(deletionVectors, "deletionVectors");
        transactionLog = Objects.requireNonNull(transactionLog, "transactionLog");
        storageRetention = Objects.requireNonNull(storageRetention, "storageRetention");
        clustering = Objects.requireNonNull(clustering, "clustering");
        protocol = Objects.requireNonNull(protocol, "protocol");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public record FileLayout(
            long fileCount,
            long totalBytes,
            long minBytes,
            long maxBytes,
            long medianBytes,
            double averageBytes,
            long smallFileCount,
            double smallFileRatio,
            long partitionCount,
            double partitionSkew) {}

    public record DeletionVectors(
            long filesWithDeletionVectors,
            long deletionVectorBytes,
            long deletedRowCount,
            double deletedRowRatio) {}

    public record TransactionLog(
            long currentVersion,
            long commitsSinceCheckpoint,
            Optional<Instant> checkpointAt,
            String checkpointType,
            long logFileCount,
            long logBytes,
            boolean logCoverageSufficient,
            long unpublishedCommitCount) {
        public TransactionLog {
            checkpointAt = checkpointAt == null ? Optional.empty() : checkpointAt;
            checkpointType = Objects.requireNonNullElse(checkpointType, "NONE");
        }
    }

    public record StorageRetention(
            long tombstoneCount, long reclaimableBytes, long retentionHours) {}

    public record Clustering(
            List<String> partitionColumns,
            List<String> clusteringColumns,
            Optional<Instant> domainUpdatedAt,
            double unclusteredFileRatio) {
        public Clustering {
            partitionColumns = List.copyOf(partitionColumns);
            clusteringColumns = List.copyOf(clusteringColumns);
            domainUpdatedAt = domainUpdatedAt == null ? Optional.empty() : domainUpdatedAt;
        }
    }

    public record Protocol(
            int minReaderVersion,
            int minWriterVersion,
            List<String> tableFeatures,
            TableAccessMode accessMode,
            boolean filesystemVisibleStateCurrent) {
        public Protocol {
            tableFeatures = List.copyOf(tableFeatures);
        }
    }
}
