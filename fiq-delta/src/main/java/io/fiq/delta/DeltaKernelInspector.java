package io.fiq.delta;

import io.fiq.delta.compatibility.delta401.Delta401KernelAdapter;
import java.util.Map;

/** Stable FIQ-owned read-only facade for Classic Delta inspection. */
public final class DeltaKernelInspector {
    private final Delta401KernelAdapter adapter = new Delta401KernelAdapter();

    public KernelTableMetrics inspect(String tablePath, Map<String, String> hadoopOptions) {
        return adapter.inspect(tablePath, hadoopOptions);
    }
}
