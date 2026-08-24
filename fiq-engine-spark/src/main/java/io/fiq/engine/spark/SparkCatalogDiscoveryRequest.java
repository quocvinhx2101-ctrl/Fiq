package io.fiq.engine.spark;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SparkCatalogDiscoveryRequest(
        UUID runId, String catalog, Map<String, String> sparkConf) {
    public SparkCatalogDiscoveryRequest {
        runId = Objects.requireNonNull(runId, "runId");
        if (catalog == null || !catalog.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Unsafe catalog identifier");
        }
        sparkConf = Map.copyOf(sparkConf == null ? Map.of() : sparkConf);
    }
}
