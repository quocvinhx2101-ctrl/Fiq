package io.fiq.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MaintenancePlanner {
    public static final long SAFE_VACUUM_RETENTION_HOURS = 168;
    public static final long SMALL_FILE_BYTES = 128L * 1024 * 1024;
    public static final long MIN_SMALL_FILES = 20;
    public static final double MIN_SMALL_FILE_RATIO = 0.30;

    private final DeltaCapabilities capabilities;
    private final Clock clock;

    public MaintenancePlanner() {
        this(new DeltaCapabilities(), Clock.systemUTC());
    }

    public MaintenancePlanner(DeltaCapabilities capabilities, Clock clock) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperationPlan plan(
            DeltaTableSnapshot snapshot,
            HealthAssessment health,
            MaintenancePolicy policy,
            OperationType operationType) {
        requireSameTableAndVersion(snapshot, health);
        var reasons = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var capability = capabilities.evaluate(snapshot).get(operationType);
        if (!capability.supported()) reasons.add(capability.reason());
        if (!policy.enabled()) reasons.add("Policy is disabled");
        if (!policy.enabledOperations().contains(operationType)) {
            reasons.add("Operation is not enabled by policy");
        }
        if (health.completeness() == HealthCompleteness.STALE) {
            reasons.add("A fresh health assessment is required");
        } else if (health.completeness() == HealthCompleteness.PARTIAL) {
            warnings.add(
                    "Some health dimensions are unavailable from the configured metadata path");
            if (operationType.isVacuum()) {
                reasons.add("VACUUM requires complete retention and transaction-log evidence");
            }
        }

        var config =
                policy.operationConfigs()
                        .getOrDefault(
                                operationType,
                                new MaintenancePolicy.OperationConfig(
                                        SAFE_VACUUM_RETENTION_HOURS,
                                        List.of(),
                                        "",
                                        "",
                                        false,
                                        false));
        var estimatedBytes = estimateBytes(operationType, health);
        var approvalRequired = capability.approvalRequired() || config.approvalRequired();

        if (operationType == OperationType.OPTIMIZE_BINPACK
                && (health.fileLayout().smallFileCount() < MIN_SMALL_FILES
                        || health.fileLayout().smallFileRatio() < MIN_SMALL_FILE_RATIO)) {
            reasons.add("Small-file debt is below the configured recommendation threshold");
        }
        if (operationType == OperationType.OPTIMIZE_ZORDER && config.zOrderColumns().isEmpty()) {
            reasons.add("Z-order columns must be explicitly configured");
        }
        if (operationType == OperationType.REORG_PURGE
                && health.deletionVectors().filesWithDeletionVectors() == 0) {
            reasons.add("No files with deletion vectors were observed");
        }
        if (operationType == OperationType.VACUUM_LITE
                && !health.transactionLog().logCoverageSufficient()) {
            reasons.add("VACUUM LITE requires transaction-log coverage for the retention window");
        }
        if (operationType.isVacuum()) {
            if (config.retentionHours() < SAFE_VACUUM_RETENTION_HOURS) {
                approvalRequired = true;
                warnings.add("Retention is below the safe 168-hour default");
            }
            warnings.add("FIQ will execute VACUUM DRY RUN before deletion");
        }
        if (operationType == OperationType.VACUUM_INVENTORY && config.inventoryTable().isBlank()) {
            reasons.add("An inventory table or query must be configured");
        }
        if (policy.maxBytesPerRun() > 0 && estimatedBytes > policy.maxBytesPerRun()) {
            if (policy.requireApprovalAboveBudget()) {
                approvalRequired = true;
                warnings.add("Estimated bytes exceed the policy budget");
            } else {
                reasons.add("Estimated bytes exceed the policy budget");
            }
        }

        return new OperationPlan(
                UUID.randomUUID(),
                snapshot.table(),
                operationType,
                snapshot.version(),
                Instant.now(clock),
                estimatedBytes,
                reasons.isEmpty(),
                approvalRequired,
                reasons,
                warnings,
                commandPreview(snapshot.table(), operationType, config));
    }

    private static long estimateBytes(OperationType type, HealthAssessment health) {
        return type.rewritesData()
                ? health.fileLayout().totalBytes()
                : health.storageRetention().reclaimableBytes();
    }

    private static void requireSameTableAndVersion(
            DeltaTableSnapshot snapshot, HealthAssessment health) {
        if (!snapshot.table().equals(health.table())) {
            throw new IllegalArgumentException(
                    "snapshot and health assessment refer to different tables");
        }
        if (snapshot.version() != health.observedVersion()) {
            throw new IllegalArgumentException(
                    "health assessment is stale for the current table version");
        }
    }

    private static String commandPreview(
            TableIdentifier table, OperationType type, MaintenancePolicy.OperationConfig config) {
        var identifier = quoteQualifiedName(table);
        var predicate = config.predicate().isBlank() ? "" : " WHERE " + config.predicate();
        return switch (type) {
            case OPTIMIZE_BINPACK, OPTIMIZE_CLUSTERING -> "OPTIMIZE " + identifier + predicate;
            case OPTIMIZE_ZORDER ->
                    "OPTIMIZE "
                            + identifier
                            + predicate
                            + " ZORDER BY ("
                            + String.join(
                                    ", ",
                                    config.zOrderColumns().stream()
                                            .map(MaintenancePlanner::quote)
                                            .toList())
                            + ")";
            case OPTIMIZE_FULL -> "OPTIMIZE " + identifier + " FULL";
            case REORG_PURGE -> "REORG TABLE " + identifier + predicate + " APPLY (PURGE)";
            case VACUUM_LITE ->
                    "VACUUM " + identifier + " LITE RETAIN " + config.retentionHours() + " HOURS";
            case VACUUM_FULL ->
                    "VACUUM " + identifier + " FULL RETAIN " + config.retentionHours() + " HOURS";
            case VACUUM_INVENTORY ->
                    "VACUUM "
                            + identifier
                            + " RETAIN "
                            + config.retentionHours()
                            + " HOURS USING INVENTORY "
                            + config.inventoryTable();
        };
    }

    private static String quoteQualifiedName(TableIdentifier table) {
        var parts = new ArrayList<String>();
        parts.add(table.catalog());
        parts.addAll(table.namespace());
        parts.add(table.name());
        return String.join(".", parts.stream().map(MaintenancePlanner::quote).toList());
    }

    private static String quote(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
