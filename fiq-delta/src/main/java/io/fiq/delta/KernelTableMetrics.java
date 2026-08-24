package io.fiq.delta;

import io.fiq.domain.FileSizeHistogram;
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
        double averageFileBytes,
        long approximateMedianFileBytes,
        long medianErrorBoundBytes,
        String medianMethod,
        FileSizeHistogram fileSizeHistogram,
        long partitionCount,
        double partitionSkew,
        long filesWithDeletionVectors,
        long deletionVectorBytes,
        long deletedRowCount,
        Long checkpointVersion,
        Instant checkpointAt,
        String checkpointType,
        long relevantLogFileCount,
        long relevantLogBytes) {
    public KernelTableMetrics {
        partitionColumns = List.copyOf(partitionColumns);
        clusteringColumns = List.copyOf(clusteringColumns);
    }
}
