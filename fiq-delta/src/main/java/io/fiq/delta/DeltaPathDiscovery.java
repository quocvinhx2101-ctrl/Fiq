package io.fiq.delta;

import io.fiq.domain.PathTarget;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public final class DeltaPathDiscovery {
    private final DeltaKernelInspector inspector = new DeltaKernelInspector();

    public List<DiscoveredPathTable> discover(
            PathTarget root,
            int maxDepth,
            int maxTables,
            Duration timeout,
            Map<String, String> hadoopOptions) {
        if (maxDepth < 0 || maxDepth > 32)
            throw new IllegalArgumentException("maxDepth is invalid");
        if (maxTables < 1 || maxTables > 100_000)
            throw new IllegalArgumentException("maxTables is invalid");
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout must be positive");
        var deadline = Instant.now().plus(timeout);
        var configuration = new Configuration(false);
        hadoopOptions.forEach(configuration::set);
        var queue = new ArrayDeque<Candidate>();
        queue.add(new Candidate(new Path(root.uri()), 0));
        var result = new ArrayList<DiscoveredPathTable>();
        try {
            var filesystem = FileSystem.get(root.uri(), configuration);
            while (!queue.isEmpty() && result.size() < maxTables) {
                if (Instant.now().isAfter(deadline)) {
                    throw new DeltaInspectionException("PATH discovery timed out", null);
                }
                var candidate = queue.removeFirst();
                if (isDeltaRoot(filesystem, candidate.path())) {
                    var target =
                            PathTarget.of(filesystem.makeQualified(candidate.path()).toString());
                    result.add(
                            new DiscoveredPathTable(
                                    target,
                                    inspector.inspect(target.uri().toString(), hadoopOptions)));
                    continue;
                }
                if (candidate.depth() >= maxDepth) continue;
                for (var child : filesystem.listStatus(candidate.path())) {
                    if (!child.isDirectory() || hidden(child.getPath().getName())) continue;
                    queue.addLast(new Candidate(child.getPath(), candidate.depth() + 1));
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new DeltaInspectionException("PATH discovery failed", exception);
        }
    }

    private static boolean isDeltaRoot(FileSystem filesystem, Path candidate) throws IOException {
        return filesystem.exists(new Path(candidate, "_delta_log"));
    }

    private static boolean hidden(String name) {
        return name.startsWith("_") || name.startsWith(".");
    }

    private record Candidate(Path path, int depth) {}

    public record DiscoveredPathTable(PathTarget target, KernelTableMetrics metrics) {}
}
