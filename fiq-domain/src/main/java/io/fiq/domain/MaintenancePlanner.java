package io.fiq.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deterministic policy evaluation over immutable assessment facts and Delta capabilities. */
public final class MaintenancePlanner {
    public static final long SAFE_VACUUM_RETENTION_HOURS = 168;

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
        return plan(snapshot, health, policy, operationType, Optional.empty());
    }

    public OperationPlan plan(
            DeltaTableSnapshot snapshot,
            HealthAssessment health,
            MaintenancePolicy policy,
            OperationType operationType,
            VacuumPreflightEvidence vacuumPreflight) {
        return plan(snapshot, health, policy, operationType, Optional.of(vacuumPreflight));
    }

    private OperationPlan plan(
            DeltaTableSnapshot snapshot,
            HealthAssessment health,
            MaintenancePolicy policy,
            OperationType operationType,
            Optional<VacuumPreflightEvidence> vacuumPreflight) {
        requireSameTableAndVersion(snapshot, health);
        var config = config(policy, operationType);
        var evaluation = evaluate(snapshot, health, policy, operationType, config, vacuumPreflight);
        var reasons = new ArrayList<String>();
        evaluation.blockers().forEach(value -> reasons.add(value.reason()));
        evaluation.conditions().stream()
                .filter(condition -> !condition.passed())
                .forEach(condition -> reasons.add(condition.reason()));
        var warnings = new ArrayList<String>();
        if (operationType == OperationType.VACUUM_FULL) {
            warnings.add("FIQ will re-run VACUUM FULL DRY RUN immediately before mutation");
            if (config.retentionHours() < SAFE_VACUUM_RETENTION_HOURS) {
                warnings.add("Retention is below the safe 168-hour default");
            }
        }
        var estimatedBytes = estimatedBytes(operationType, evaluation);
        if (policy.maxBytesPerRun() > 0 && estimatedBytes > policy.maxBytesPerRun()) {
            warnings.add("Estimated bytes exceed the policy byte budget");
        }
        return new OperationPlan(
                UUID.randomUUID(),
                snapshot.table(),
                operationType,
                snapshot.version(),
                Instant.now(clock),
                estimatedBytes,
                evaluation.decision() == PolicyDecision.ELIGIBLE
                        || evaluation.decision() == PolicyDecision.APPROVAL_REQUIRED,
                evaluation.approvalRequired(),
                reasons,
                warnings,
                evaluation,
                commandPreview(snapshot.table(), operationType, config));
    }

    public PolicyEvaluation evaluate(
            DeltaTableSnapshot snapshot,
            HealthAssessment health,
            MaintenancePolicy policy,
            OperationType operationType) {
        requireSameTableAndVersion(snapshot, health);
        return evaluate(
                snapshot,
                health,
                policy,
                operationType,
                config(policy, operationType),
                Optional.empty());
    }

    private PolicyEvaluation evaluate(
            DeltaTableSnapshot snapshot,
            HealthAssessment health,
            MaintenancePolicy policy,
            OperationType operationType,
            MaintenancePolicy.OperationConfig config,
            Optional<VacuumPreflightEvidence> vacuumPreflight) {
        var blockers = new ArrayList<PolicyBlocker>();
        var conditions = new ArrayList<PolicyCondition>();
        var observations = new LinkedHashMap<String, Object>();
        var thresholds = new LinkedHashMap<String, Object>();
        var capability = capabilities.evaluate(snapshot).get(operationType);
        if (!capability.supported()) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_CAPABILITY_BLOCKED",
                            HealthDimension.PROTOCOL,
                            capability.reason()));
        }
        if (!policy.enabled()) {
            blockers.add(new PolicyBlocker("FIQ_POLICY_DISABLED", null, "Policy is disabled"));
        }
        if (!policy.enabledOperations().contains(operationType)) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_OPERATION_NOT_ENABLED",
                            null,
                            "Operation is not enabled by policy"));
        }
        requireDimension(health, HealthDimension.PROTOCOL, blockers);
        if (!health.protocol().mutationQualified()) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_RUNTIME_NOT_QUALIFIED",
                            HealthDimension.PROTOCOL,
                            "Mutation requires exactly Spark 4.0.1 and Delta Lake 4.0.1"));
        }

        if (operationType == OperationType.OPTIMIZE_BINPACK) {
            evaluateOptimize(
                    health,
                    config.fileLayoutPolicy(),
                    conditions,
                    observations,
                    thresholds,
                    blockers);
        } else if (operationType == OperationType.VACUUM_FULL) {
            evaluateVacuum(
                    health,
                    config,
                    vacuumPreflight,
                    conditions,
                    observations,
                    thresholds,
                    blockers);
        }

        var approvalRequired = capability.approvalRequired() || config.approvalRequired();
        if (operationType == OperationType.VACUUM_FULL
                && config.retentionHours() < SAFE_VACUUM_RETENTION_HOURS) {
            approvalRequired = true;
        }
        var estimatedBytes = estimatedBytes(operationType, observations);
        var phaseOneOperation =
                operationType == OperationType.OPTIMIZE_BINPACK
                        || operationType == OperationType.VACUUM_FULL;
        if (phaseOneOperation
                && policy.maxBytesPerRun() > 0
                && estimatedBytes != null
                && estimatedBytes > policy.maxBytesPerRun()) {
            if (policy.requireApprovalAboveBudget()) approvalRequired = true;
            else {
                blockers.add(
                        new PolicyBlocker(
                                "FIQ_BYTE_BUDGET_EXCEEDED",
                                null,
                                "Estimated bytes exceed the policy byte budget"));
            }
        }
        if (phaseOneOperation && policy.maxBytesPerRun() > 0 && estimatedBytes == null) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_BYTE_ESTIMATE_UNKNOWN",
                            operationType == OperationType.VACUUM_FULL
                                    ? HealthDimension.RETENTION
                                    : HealthDimension.FILE_LAYOUT,
                            "Byte budget cannot be evaluated because the byte estimate is UNKNOWN"));
        }

        PolicyDecision decision;
        if (!blockers.isEmpty()) decision = PolicyDecision.BLOCKED;
        else if (conditions.stream().anyMatch(condition -> !condition.passed())) {
            decision = PolicyDecision.NOT_ELIGIBLE;
        } else if (approvalRequired) decision = PolicyDecision.APPROVAL_REQUIRED;
        else decision = PolicyDecision.ELIGIBLE;
        return new PolicyEvaluation(
                operationType,
                health.observedVersion(),
                Instant.now(clock),
                decision,
                approvalRequired,
                observations,
                thresholds,
                conditions,
                blockers);
    }

    private static void evaluateOptimize(
            HealthAssessment health,
            MaintenancePolicy.FileLayoutPolicy policy,
            List<PolicyCondition> conditions,
            LinkedHashMap<String, Object> observations,
            LinkedHashMap<String, Object> thresholds,
            List<PolicyBlocker> blockers) {
        requireDimension(health, HealthDimension.FILE_LAYOUT, blockers);
        var layout = health.fileLayout();
        var histogram = layout.fileSizeHistogram();
        var smallFiles = histogram.countBelow(policy.smallFileThresholdBytes());
        var ratio =
                layout.activeFileCount() == 0 ? 0 : (double) smallFiles / layout.activeFileCount();
        var rewrite = histogram.estimateBytesBelow(policy.smallFileThresholdBytes());
        var targetFiles =
                rewrite.valueBytes() == 0
                        ? 0
                        : Math.max(
                                1,
                                divideCeiling(rewrite.valueBytes(), policy.targetFileSizeBytes()));
        var expectedReduction =
                smallFiles == 0
                        ? 0
                        : (double) Math.max(0, smallFiles - Math.min(smallFiles, targetFiles))
                                / smallFiles;
        observations.put("activeFileCount", layout.activeFileCount());
        observations.put("filesBelowThreshold", smallFiles);
        observations.put("smallFileRatio", ratio);
        observations.put("estimatedRewriteBytes", rewrite.valueBytes());
        observations.put("estimatedRewriteErrorBytes", rewrite.errorBoundBytes());
        observations.put("estimatedTargetFileCount", targetFiles);
        observations.put("expectedReductionRatio", expectedReduction);
        thresholds.put("smallFileThresholdBytes", policy.smallFileThresholdBytes());
        thresholds.put("minimumSmallFileCount", policy.minimumSmallFileCount());
        thresholds.put("minimumSmallFileRatio", policy.minimumSmallFileRatio());
        thresholds.put("minimumRewriteBytes", policy.minimumRewriteBytes());
        thresholds.put("targetFileSizeBytes", policy.targetFileSizeBytes());
        thresholds.put("minimumExpectedReductionRatio", policy.minimumExpectedReductionRatio());
        conditions.add(
                condition(
                        "filesBelowThreshold",
                        smallFiles,
                        ">=",
                        policy.minimumSmallFileCount(),
                        smallFiles >= policy.minimumSmallFileCount()));
        conditions.add(
                condition(
                        "smallFileRatio",
                        ratio,
                        ">=",
                        policy.minimumSmallFileRatio(),
                        ratio >= policy.minimumSmallFileRatio()));
        conditions.add(
                condition(
                        "estimatedRewriteBytes",
                        rewrite.valueBytes(),
                        ">=",
                        policy.minimumRewriteBytes(),
                        rewrite.valueBytes() >= policy.minimumRewriteBytes()));
        conditions.add(
                condition(
                        "expectedReductionRatio",
                        expectedReduction,
                        ">=",
                        policy.minimumExpectedReductionRatio(),
                        expectedReduction >= policy.minimumExpectedReductionRatio()));
    }

    private static void evaluateVacuum(
            HealthAssessment health,
            MaintenancePolicy.OperationConfig config,
            Optional<VacuumPreflightEvidence> preflight,
            List<PolicyCondition> conditions,
            LinkedHashMap<String, Object> observations,
            LinkedHashMap<String, Object> thresholds,
            List<PolicyBlocker> blockers) {
        requireDimension(health, HealthDimension.TRANSACTION_LOG, blockers);
        thresholds.put("retentionHours", config.retentionHours());
        thresholds.put("minimumCandidateCount", config.vacuumPolicy().minimumCandidateCount());
        thresholds.put("minimumReclaimableBytes", config.vacuumPolicy().minimumReclaimableBytes());
        if (preflight.isEmpty()) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_VACUUM_PREFLIGHT_REQUIRED",
                            HealthDimension.RETENTION,
                            "VACUUM FULL requires a real DRY RUN before policy evaluation"));
            return;
        }
        var value = preflight.get();
        if (value.plannedVersion() != health.observedVersion()
                || value.retentionHours() != config.retentionHours()) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_VACUUM_PREFLIGHT_STALE",
                            HealthDimension.RETENTION,
                            "VACUUM preflight does not match the assessment version and retention"));
            return;
        }
        observations.put("candidateCount", value.candidateCount());
        observations.put("candidateHash", value.candidateHash());
        if (value.candidateBytes() != null)
            observations.put("candidateBytes", value.candidateBytes());
        conditions.add(
                condition(
                        "candidateCount",
                        value.candidateCount(),
                        ">=",
                        config.vacuumPolicy().minimumCandidateCount(),
                        value.candidateCount() >= config.vacuumPolicy().minimumCandidateCount()));
        if (config.vacuumPolicy().minimumReclaimableBytes() > 0 && value.candidateBytes() == null) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_VACUUM_BYTES_UNKNOWN",
                            HealthDimension.RETENTION,
                            "VACUUM candidate bytes are UNKNOWN and cannot satisfy the configured threshold"));
        } else {
            var bytes = Objects.requireNonNullElse(value.candidateBytes(), 0L);
            conditions.add(
                    condition(
                            "candidateBytes",
                            bytes,
                            ">=",
                            config.vacuumPolicy().minimumReclaimableBytes(),
                            bytes >= config.vacuumPolicy().minimumReclaimableBytes()));
        }
    }

    private static void requireDimension(
            HealthAssessment health, HealthDimension dimension, List<PolicyBlocker> blockers) {
        var metadata = health.dimensions().get(dimension);
        if (metadata.completeness() != HealthCompleteness.COMPLETE) {
            blockers.add(
                    new PolicyBlocker(
                            "FIQ_" + dimension.name() + "_INCOMPLETE",
                            dimension,
                            metadata.incompleteReason()
                                    .orElse(dimension + " evidence is incomplete")));
        }
    }

    private static PolicyCondition condition(
            String metric, Object observed, String operator, Object threshold, boolean passed) {
        return new PolicyCondition(
                metric,
                observed,
                operator,
                threshold,
                passed,
                metric
                        + " "
                        + observed
                        + (passed ? " " : " does not satisfy ")
                        + operator
                        + " "
                        + threshold);
    }

    private static MaintenancePolicy.OperationConfig config(
            MaintenancePolicy policy, OperationType operationType) {
        return policy.operationConfigs()
                .getOrDefault(
                        operationType,
                        new MaintenancePolicy.OperationConfig(
                                SAFE_VACUUM_RETENTION_HOURS, List.of(), "", "", false, false));
    }

    private static long estimatedBytes(OperationType operationType, PolicyEvaluation evaluation) {
        return Objects.requireNonNullElse(
                estimatedBytes(operationType, evaluation.observations()), 0L);
    }

    private static Long estimatedBytes(
            OperationType operationType, java.util.Map<String, Object> observations) {
        var key =
                operationType == OperationType.VACUUM_FULL
                        ? "candidateBytes"
                        : "estimatedRewriteBytes";
        var value = observations.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long divideCeiling(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
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
        var identifier = ExecutionTargetRenderer.sql(table.executionTarget());
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

    private static String quote(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
