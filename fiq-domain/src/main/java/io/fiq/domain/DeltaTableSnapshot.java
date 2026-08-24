package io.fiq.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record DeltaTableSnapshot(
        TableIdentifier table,
        TableAccessMode accessMode,
        long version,
        Instant observedAt,
        int minReaderVersion,
        int minWriterVersion,
        Set<String> tableFeatures,
        List<String> partitionColumns,
        List<String> clusteringColumns,
        Map<String, String> properties,
        boolean catalogMaintenanceAllowed,
        boolean filesystemVisibleStateCurrent) {

    public DeltaTableSnapshot {
        table = Objects.requireNonNull(table, "table");
        accessMode = Objects.requireNonNull(accessMode, "accessMode");
        if (version < -1) throw new IllegalArgumentException("version must be >= -1");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        tableFeatures = Set.copyOf(Objects.requireNonNull(tableFeatures, "tableFeatures"));
        partitionColumns =
                List.copyOf(Objects.requireNonNull(partitionColumns, "partitionColumns"));
        clusteringColumns =
                List.copyOf(Objects.requireNonNull(clusteringColumns, "clusteringColumns"));
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    public boolean hasFeature(String feature) {
        return tableFeatures.contains(feature);
    }

    public boolean isLiquidClustered() {
        return hasFeature("clustering") || !clusteringColumns.isEmpty();
    }
}
