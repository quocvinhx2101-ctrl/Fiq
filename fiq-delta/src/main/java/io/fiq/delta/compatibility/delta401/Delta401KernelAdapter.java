package io.fiq.delta.compatibility.delta401;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.clustering.ClusteringMetadataDomain;
import io.fiq.delta.DeltaInspectionException;
import io.fiq.delta.KernelTableMetrics;
import io.fiq.domain.FileSizeHistogram;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Delta Kernel 4.0.1 compatibility boundary. InternalScanFileUtils and ClusteringMetadataDomain are
 * internal Delta APIs and must not escape this package.
 */
public final class Delta401KernelAdapter {
    private static final Pattern COMMIT_FILE = Pattern.compile("^(\\d{20})\\.json$");
    private static final Pattern CHECKPOINT_FILE = Pattern.compile("^(\\d{20})\\.checkpoint\\..+$");

    public KernelTableMetrics inspect(String tablePath, Map<String, String> hadoopOptions) {
        if (tablePath == null || tablePath.isBlank()) {
            throw new IllegalArgumentException("tablePath is required");
        }
        var configuration = configuration(hadoopOptions);
        var engine = DefaultEngine.create(configuration);
        var table = Table.forPath(engine, tablePath);
        var snapshot = table.getLatestSnapshot(engine);
        var histogram = new HistogramAccumulator();
        var partitions = new HashMap<String, Long>();
        long totalBytes = 0;
        long minBytes = Long.MAX_VALUE;
        long maxBytes = 0;
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
                        histogram.add(size);
                        totalBytes += size;
                        minBytes = Math.min(minBytes, size);
                        maxBytes = Math.max(maxBytes, size);
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

        var fileCount = histogram.count();
        var quantile = histogram.median(maxBytes);
        var clustering =
                snapshot.getDomainMetadata("delta.clustering")
                        .map(ClusteringMetadataDomain::fromJsonConfiguration)
                        .map(
                                domain ->
                                        domain.getClusteringColumns().stream()
                                                .map(Object::toString)
                                                .toList())
                        .orElseGet(List::of);
        var log = inspectLog(URI.create(tablePath), configuration);
        return new KernelTableMetrics(
                snapshot.getVersion(),
                Instant.ofEpochMilli(snapshot.getTimestamp(engine)),
                snapshot.getPartitionColumnNames(),
                clustering,
                fileCount,
                totalBytes,
                fileCount == 0 ? 0 : minBytes,
                maxBytes,
                fileCount == 0 ? 0 : (double) totalBytes / fileCount,
                quantile.value(),
                quantile.errorBound(),
                quantile.method(),
                histogram.toValue(),
                partitions.size(),
                partitionSkew(partitions),
                filesWithDvs,
                dvBytes,
                deletedRows,
                log.checkpointVersion(),
                log.checkpointAt(),
                log.checkpointType(),
                log.fileCount(),
                log.bytes());
    }

    private static LogFacts inspectLog(URI tableUri, Configuration configuration) {
        try {
            var filesystem = FileSystem.get(tableUri, configuration);
            var logPath = new Path(new Path(tableUri), "_delta_log");
            long fileCount = 0;
            long bytes = 0;
            Long checkpointVersion = null;
            Instant checkpointAt = null;
            for (var status : filesystem.listStatus(logPath)) {
                if (!status.isFile()) continue;
                var name = status.getPath().getName();
                var commit = COMMIT_FILE.matcher(name);
                var checkpoint = CHECKPOINT_FILE.matcher(name);
                if (commit.matches() || checkpoint.matches()) {
                    fileCount++;
                    bytes += status.getLen();
                }
                if (checkpoint.matches()) {
                    var version = Long.parseLong(checkpoint.group(1));
                    if (checkpointVersion == null || version > checkpointVersion) {
                        checkpointVersion = version;
                        checkpointAt = Instant.ofEpochMilli(status.getModificationTime());
                    }
                }
            }
            return new LogFacts(
                    checkpointVersion,
                    checkpointAt,
                    checkpointVersion == null ? null : "OBSERVED_CHECKPOINT_FILE",
                    fileCount,
                    bytes);
        } catch (java.io.IOException exception) {
            throw new DeltaInspectionException(
                    "Could not inspect the Delta transaction log", exception);
        }
    }

    private static Configuration configuration(Map<String, String> hadoopOptions) {
        var configuration = new Configuration(false);
        Objects.requireNonNullElse(hadoopOptions, Map.<String, String>of())
                .forEach(configuration::set);
        return configuration;
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

    private static final class HistogramAccumulator {
        private final long[] buckets = new long[FileSizeHistogram.BUCKET_COUNT];
        private long overflow;
        private long count;

        void add(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Delta AddFile size is negative");
            count++;
            var bucket = bytes / FileSizeHistogram.MIB;
            if (bucket >= buckets.length) overflow++;
            else buckets[Math.toIntExact(bucket)]++;
        }

        long count() {
            return count;
        }

        FileSizeHistogram toValue() {
            var values = new ArrayList<Long>(buckets.length);
            for (var count : buckets) values.add(count);
            return new FileSizeHistogram(FileSizeHistogram.MIB, values, overflow);
        }

        Quantile median(long maximumBytes) {
            if (count == 0) return new Quantile(0, 0, "EMPTY");
            var rank = (count - 1) / 2;
            long seen = 0;
            for (var index = 0; index < buckets.length; index++) {
                seen += buckets[index];
                if (seen > rank) {
                    var lower = index * FileSizeHistogram.MIB;
                    return new Quantile(
                            lower + FileSizeHistogram.MIB / 2,
                            FileSizeHistogram.MIB / 2,
                            "FIXED_1_MIB_HISTOGRAM");
                }
            }
            var lower = FileSizeHistogram.MIB * FileSizeHistogram.BUCKET_COUNT;
            return new Quantile(
                    lower + Math.max(0, maximumBytes - lower) / 2,
                    Math.max(0, maximumBytes - lower) / 2,
                    "FIXED_1_MIB_HISTOGRAM_OVERFLOW");
        }
    }

    private record Quantile(long value, long errorBound, String method) {}

    private record LogFacts(
            Long checkpointVersion,
            Instant checkpointAt,
            String checkpointType,
            long fileCount,
            long bytes) {}
}
