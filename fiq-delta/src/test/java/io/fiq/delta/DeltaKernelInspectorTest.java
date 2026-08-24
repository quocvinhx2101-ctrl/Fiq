package io.fiq.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.fiq.domain.FileSizeHistogram;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeltaKernelInspectorTest {
    @Test
    void readsFileAndDeletionVectorMetricsWithoutSpark() throws Exception {
        var resource = getClass().getResource("/simple-table");
        var path = Path.of(resource.toURI()).toUri().toString();

        var metrics = new DeltaKernelInspector().inspect(path, Map.of());

        assertThat(metrics.version()).isZero();
        assertThat(metrics.fileCount()).isEqualTo(2);
        assertThat(metrics.totalBytes()).isEqualTo(3072);
        assertThat(metrics.fileSizeHistogram().countBelow(FileSizeHistogram.MIB)).isEqualTo(2);
        assertThat(metrics.fileSizeHistogram().totalCount()).isEqualTo(metrics.fileCount());
        assertThat(metrics.partitionCount()).isEqualTo(1);
        assertThat(metrics.filesWithDeletionVectors()).isEqualTo(1);
        assertThat(metrics.deletionVectorBytes()).isEqualTo(1);
        assertThat(metrics.deletedRowCount()).isEqualTo(3);
    }
}
