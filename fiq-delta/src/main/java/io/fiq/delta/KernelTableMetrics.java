package io.fiq.delta;

import java.time.Instant;
import java.util.List;

public record KernelTableMetrics(
        long version,
        Instant snapshotTimestamp,
        List<String> partitionColumns,
        List<String> clusteringColumns,
        long fileCount,
        long totalBytes,
        long minFileBytes,
        long maxFileBytes,
        long medianFileBytes,
        double averageFileBytes,
        long smallFileCount,
        double smallFileRatio,
        long partitionCount,
        double partitionSkew,
        long filesWithDeletionVectors,
        long deletionVectorBytes,
        long deletedRowCount) {
    public KernelTableMetrics {
        partitionColumns = List.copyOf(partitionColumns);
        clusteringColumns = List.copyOf(clusteringColumns);
    }
}
