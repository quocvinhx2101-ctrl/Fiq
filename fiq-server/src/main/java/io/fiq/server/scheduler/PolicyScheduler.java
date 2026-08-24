package io.fiq.server.scheduler;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationType;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.service.OperationService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PolicyScheduler {
    private static final Duration FIRE_LOOKBACK = Duration.ofSeconds(75);

    @Inject FiqStore store;
    @Inject OperationService operations;
    @Inject FiqEventBus events;

    @ConfigProperty(name = "fiq.scheduler.enabled")
    boolean enabled;

    @ConfigProperty(name = "fiq.scheduler.lease-seconds")
    int leaseSeconds;

    private final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    private final String owner = instanceOwner();

    @Scheduled(every = "15s", concurrentExecution = ConcurrentExecution.SKIP)
    void tick() {
        if (!enabled) return;
        var now = Instant.now();
        for (var reference : store.enabledPolicyReferences()) {
            try {
                schedulePolicy(reference, now);
            } catch (RuntimeException exception) {
                events.publish(
                        reference.workspaceId(),
                        "POLICY_SCHEDULER_FAILED",
                        java.util.Map.of(
                                "policyId", reference.policyId(), "message", message(exception)));
            }
        }
    }

    private void schedulePolicy(FiqStore.PolicyReference reference, Instant now) {
        var policy = store.loadPolicy(reference.workspaceId(), reference.policyId());
        var zonedNow = now.atZone(policy.timezone());
        var cron = cronParser.parse(policy.cron());
        cron.validate();
        var lastFire = ExecutionTime.forCron(cron).lastExecution(zonedNow).orElse(null);
        if (lastFire == null
                || Duration.between(lastFire.toInstant(), now).compareTo(FIRE_LOOKBACK) > 0
                || !withinWindow(policy.maintenanceWindow(), zonedNow)) {
            return;
        }
        for (var operation : policy.enabledOperations()) {
            if (operation != OperationType.OPTIMIZE_BINPACK
                    && operation != OperationType.VACUUM_FULL) continue;
            var config = config(policy, operation);
            if (!config.automatic()) continue;
            for (var tableId : store.matchingActiveTables(reference.workspaceId(), policy)) {
                if (store.activePolicyOperationCount(reference.workspaceId(), policy.id())
                        >= policy.maxConcurrentOperations()) return;
                var scheduled = lastFire.toInstant();
                var fireKey =
                        policy.id()
                                + ":"
                                + tableId
                                + ":"
                                + operation.name()
                                + ":"
                                + scheduled.toEpochMilli();
                if (!store.claimPolicyFire(
                        policy.id(), tableId, operation, scheduled, fireKey, owner, leaseSeconds))
                    continue;
                try {
                    var planned =
                            operations.schedule(
                                    reference.workspaceId(),
                                    new ApiModels.PlanRequest(
                                            tableId,
                                            policy.id(),
                                            operation,
                                            "schedule:" + fireKey));
                    store.completePolicyFire(fireKey, planned.state().name(), planned.id(), null);
                } catch (RuntimeException exception) {
                    store.completePolicyFire(fireKey, "FAILED", null, message(exception));
                    throw exception;
                }
            }
        }
    }

    static boolean withinWindow(MaintenancePolicy.MaintenanceWindow window, ZonedDateTime now) {
        var time = now.toLocalTime();
        if (window.start().compareTo(window.end()) <= 0) {
            return window.days().contains(now.getDayOfWeek())
                    && !time.isBefore(window.start())
                    && !time.isAfter(window.end());
        }
        if (!time.isBefore(window.start())) {
            return window.days().contains(now.getDayOfWeek());
        }
        return !time.isAfter(window.end())
                && window.days().contains(now.minusDays(1).getDayOfWeek());
    }

    private static MaintenancePolicy.OperationConfig config(
            MaintenancePolicy policy, OperationType operation) {
        return policy.operationConfigs()
                .getOrDefault(
                        operation,
                        new MaintenancePolicy.OperationConfig(
                                168, java.util.List.of(), "", "", false, false));
    }

    private static String message(Throwable value) {
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }

    private static String instanceOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-policy-" + UUID.randomUUID();
        } catch (Exception exception) {
            return "fiq-policy-" + UUID.randomUUID();
        }
    }
}
