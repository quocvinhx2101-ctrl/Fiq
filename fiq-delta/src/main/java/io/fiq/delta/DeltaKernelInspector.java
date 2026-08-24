package io.fiq.delta;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.clustering.ClusteringMetadataDomain;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hadoop.conf.Configuration;

/** Read-only classic Delta inspection. Catalog-managed tables must not enter this adapter. */
public final class DeltaKernelInspector {
    public static final long DEFAULT_SMALL_FILE_BYTES = 128L * 1024 * 1024;

    public KernelTableMetrics inspect(String tablePath, Map<String, String> hadoopOptions) {
        return inspect(tablePath, hadoopOptions, DEFAULT_SMALL_FILE_BYTES);
    }

    public KernelTableMetrics inspect(
            String tablePath, Map<String, String> hadoopOptions, long smallFileThresholdBytes) {
        if (tablePath == null || tablePath.isBlank()) {
            throw new IllegalArgumentException("tablePath is required");
        }
        if (smallFileThresholdBytes <= 0) {
            throw new IllegalArgumentException("smallFileThresholdBytes must be positive");
        }
        var engine = engine(hadoopOptions);
        var table = Table.forPath(engine, tablePath);
        var snapshot = table.getLatestSnapshot(engine);
        var sizes = new ArrayList<Long>();
        var partitions = new HashMap<String, Long>();
        long totalBytes = 0;
        long minBytes = Long.MAX_VALUE;
        long maxBytes = 0;
        long smallFiles = 0;
        long filesWithDvs = 0;
        long dvBytes = 0;
        long deletedRows = 0;

        var scan = snapshot.getScanBuilder().build();
        try (var batches = scan.getScanFiles(engine)) {
            while (batches.hasNext()) {
                var batch = batches.next();
                try (var rows = batch.getRows()) {
                    while (rows.hasNext()) {
                        var row = rows.next();
                        var file = InternalScanFileUtils.getAddFileStatus(row);
                        var size = file.getSize();
                        sizes.add(size);
                        totalBytes += size;
                        minBytes = Math.min(minBytes, size);
                        maxBytes = Math.max(maxBytes, size);
                        if (size < smallFileThresholdBytes) smallFiles++;
                        partitions.merge(
                                partitionKey(InternalScanFileUtils.getPartitionValues(row)),
                                1L,
                                Long::sum);
                        var deletionVector =
                                InternalScanFileUtils.getDeletionVectorDescriptorFromRow(row);
                        if (deletionVector != null) {
                            filesWithDvs++;
                            dvBytes += deletionVector.getSizeInBytes();
                            deletedRows += deletionVector.getCardinality();
                        }
                    }
                }
            }
        } catch (java.io.IOException exception) {
            throw new DeltaInspectionException("Could not close the Delta scan", exception);
        }

        sizes.sort(Comparator.naturalOrder());
        var fileCount = sizes.size();
        var median = fileCount == 0 ? 0 : sizes.get(fileCount / 2);
        var average = fileCount == 0 ? 0 : (double) totalBytes / fileCount;
        var clustering =
                snapshot.getDomainMetadata("delta.clustering")
                        .map(ClusteringMetadataDomain::fromJsonConfiguration)
                        .map(
                                domain ->
                                        domain.getClusteringColumns().stream()
                                                .map(Object::toString)
                                                .toList())
                        .orElseGet(List::of);
        return new KernelTableMetrics(
                snapshot.getVersion(),
                Instant.ofEpochMilli(snapshot.getTimestamp(engine)),
                snapshot.getPartitionColumnNames(),
                clustering,
                fileCount,
                totalBytes,
                fileCount == 0 ? 0 : minBytes,
                maxBytes,
                median,
                average,
                smallFiles,
                fileCount == 0 ? 0 : (double) smallFiles / fileCount,
                partitions.size(),
                partitionSkew(partitions),
                filesWithDvs,
                dvBytes,
                deletedRows);
    }

    private static Engine engine(Map<String, String> hadoopOptions) {
        var configuration = new Configuration(false);
        Objects.requireNonNullElse(hadoopOptions, Map.<String, String>of())
                .forEach(configuration::set);
        return DefaultEngine.create(configuration);
    }

    private static String partitionKey(Map<String, String> values) {
        if (values == null || values.isEmpty()) return "__unpartitioned__";
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "/" + right)
                .orElse("__unpartitioned__");
    }

    private static double partitionSkew(Map<String, Long> partitions) {
        if (partitions.size() < 2) return 1.0;
        var average = partitions.values().stream().mapToLong(Long::longValue).average().orElse(0);
        if (average == 0) return 1.0;
        return partitions.values().stream().mapToDouble(value -> value / average).max().orElse(1.0);
    }

    public static final class DeltaInspectionException extends RuntimeException {
        public DeltaInspectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
