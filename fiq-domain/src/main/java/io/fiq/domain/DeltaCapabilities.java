package io.fiq.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class DeltaCapabilities {
    private static final Set<String> KNOWN_4_0_1_FEATURES =
            Set.of(
                    "appendOnly",
                    "invariants",
                    "checkConstraints",
                    "changeDataFeed",
                    "generatedColumns",
                    "columnMapping",
                    "identityColumns",
                    "deletionVectors",
                    "timestampNtz",
                    "v2Checkpoint",
                    "domainMetadata",
                    "clustering",
                    "rowTracking",
                    "typeWidening",
                    "typeWidening-preview",
                    "allowColumnDefaults",
                    "inCommitTimestamp",
                    "vacuumProtocolCheck",
                    "checkpointProtection",
                    "coordinatedCommits-preview",
                    "catalogManaged");

    public Map<OperationType, Capability> evaluate(DeltaTableSnapshot snapshot) {
        var result = new EnumMap<OperationType, Capability>(OperationType.class);
        var unknown =
                snapshot.tableFeatures().stream()
                        .filter(feature -> !KNOWN_4_0_1_FEATURES.contains(feature))
                        .sorted()
                        .toList();
        if (!unknown.isEmpty()) {
            var reason =
                    "Unknown Delta table features require read-only mode: "
                            + String.join(", ", unknown);
            for (var type : OperationType.values())
                result.put(type, Capability.unsupported(reason));
            return Map.copyOf(result);
        }

        if (snapshot.accessMode() == TableAccessMode.CATALOG_MANAGED
                && !snapshot.catalogMaintenanceAllowed()) {
            for (var type : OperationType.values()) {
                result.put(
                        type,
                        Capability.unsupported(
                                "The managing catalog did not authorize maintenance"));
            }
            return Map.copyOf(result);
        }

        result.put(OperationType.OPTIMIZE_BINPACK, Capability.supported(false));
        var deferred = "Operation is not qualified in FIQ Phase 1";
        result.put(OperationType.OPTIMIZE_ZORDER, Capability.unsupported(deferred));
        result.put(OperationType.OPTIMIZE_CLUSTERING, Capability.unsupported(deferred));
        result.put(OperationType.OPTIMIZE_FULL, Capability.unsupported(deferred));
        result.put(OperationType.REORG_PURGE, Capability.unsupported(deferred));
        result.put(OperationType.VACUUM_LITE, Capability.unsupported(deferred));
        result.put(OperationType.VACUUM_INVENTORY, Capability.unsupported(deferred));
        result.put(
                OperationType.VACUUM_FULL,
                snapshot.accessMode() == TableAccessMode.CATALOG_MANAGED
                        ? Capability.unsupported(
                                "Delta 4.0.1 blocks VACUUM for catalog-managed managed tables")
                        : Capability.supported(false));
        return Map.copyOf(result);
    }
}
