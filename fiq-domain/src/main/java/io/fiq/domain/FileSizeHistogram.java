package io.fiq.domain;

import java.util.List;
import java.util.Objects;

/** A bounded-memory file-size distribution with exact whole-MiB policy boundaries. */
public record FileSizeHistogram(
        long bucketWidthBytes, List<Long> bucketCounts, long overflowCount) {
    public static final long MIB = 1024L * 1024;
    public static final int BUCKET_COUNT = 1024;

    public FileSizeHistogram {
        if (bucketWidthBytes != MIB) {
            throw new IllegalArgumentException("bucketWidthBytes must be exactly 1 MiB");
        }
        bucketCounts = List.copyOf(Objects.requireNonNull(bucketCounts, "bucketCounts"));
        if (bucketCounts.size() != BUCKET_COUNT) {
            throw new IllegalArgumentException("bucketCounts must contain 1024 buckets");
        }
        if (overflowCount < 0
                || bucketCounts.stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("histogram counts must be non-negative");
        }
    }

    public long countBelow(long thresholdBytes) {
        validateThreshold(thresholdBytes);
        var exclusiveBucket = Math.toIntExact(thresholdBytes / bucketWidthBytes);
        return bucketCounts.subList(0, exclusiveBucket).stream().mapToLong(Long::longValue).sum();
    }

    public long totalCount() {
        return overflowCount + bucketCounts.stream().mapToLong(Long::longValue).sum();
    }

    public ByteEstimate estimateBytesBelow(long thresholdBytes) {
        validateThreshold(thresholdBytes);
        var exclusiveBucket = Math.toIntExact(thresholdBytes / bucketWidthBytes);
        long value = 0;
        long error = 0;
        for (var index = 0; index < exclusiveBucket; index++) {
            var count = bucketCounts.get(index);
            value =
                    Math.addExact(
                            value,
                            Math.multiplyExact(
                                    count, index * bucketWidthBytes + bucketWidthBytes / 2));
            error = Math.addExact(error, Math.multiplyExact(count, bucketWidthBytes / 2));
        }
        return new ByteEstimate(value, error, "FIXED_1_MIB_HISTOGRAM_MIDPOINT");
    }

    public static void validateThreshold(long thresholdBytes) {
        if (thresholdBytes < MIB
                || thresholdBytes > MIB * BUCKET_COUNT
                || thresholdBytes % MIB != 0) {
            throw new IllegalArgumentException(
                    "small-file threshold must be a whole MiB between 1 MiB and 1 GiB");
        }
    }

    public record ByteEstimate(long valueBytes, long errorBoundBytes, String method) {}
}
