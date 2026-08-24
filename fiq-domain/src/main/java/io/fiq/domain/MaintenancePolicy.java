package io.fiq.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record MaintenancePolicy(
        UUID id,
        UUID workspaceId,
        String name,
        boolean enabled,
        Selector selector,
        String cron,
        ZoneId timezone,
        MaintenanceWindow maintenanceWindow,
        Set<OperationType> enabledOperations,
        Map<OperationType, OperationConfig> operationConfigs,
        long maxBytesPerRun,
        int maxConcurrentOperations,
        boolean requireApprovalAboveBudget) {

    public MaintenancePolicy {
        id = Objects.requireNonNull(id, "id");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        selector = Objects.requireNonNull(selector, "selector");
        if (cron == null || cron.isBlank()) throw new IllegalArgumentException("cron is required");
        timezone = Objects.requireNonNull(timezone, "timezone");
        maintenanceWindow = Objects.requireNonNull(maintenanceWindow, "maintenanceWindow");
        enabledOperations = Set.copyOf(enabledOperations);
        operationConfigs = Map.copyOf(operationConfigs);
        if (maxBytesPerRun < 0) throw new IllegalArgumentException("maxBytesPerRun must be >= 0");
        if (maxConcurrentOperations < 1) {
            throw new IllegalArgumentException("maxConcurrentOperations must be >= 1");
        }
    }

    public record Selector(
            String environmentGlob,
            String catalogGlob,
            String namespaceGlob,
            String tableGlob,
            Map<String, String> requiredTags) {
        public Selector {
            environmentGlob = normalizeGlob(environmentGlob);
            catalogGlob = normalizeGlob(catalogGlob);
            namespaceGlob = normalizeGlob(namespaceGlob);
            tableGlob = normalizeGlob(tableGlob);
            requiredTags = Map.copyOf(requiredTags);
        }

        public boolean matches(TableIdentifier table, Map<String, String> tags) {
            return globMatches(environmentGlob, table.environment())
                    && globMatches(catalogGlob, table.catalog())
                    && globMatches(namespaceGlob, String.join(".", table.namespace()))
                    && globMatches(tableGlob, table.name())
                    && requiredTags.entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(tags.get(entry.getKey())));
        }

        private static String normalizeGlob(String value) {
            return value == null || value.isBlank() ? "*" : value;
        }

        private static boolean globMatches(String glob, String value) {
            var regex = Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
            return value.matches(regex);
        }
    }

    public record MaintenanceWindow(Set<DayOfWeek> days, LocalTime start, LocalTime end) {
        public MaintenanceWindow {
            days = Set.copyOf(days);
            start = Objects.requireNonNull(start, "start");
            end = Objects.requireNonNull(end, "end");
            if (days.isEmpty()) throw new IllegalArgumentException("at least one day is required");
        }
    }

    public record OperationConfig(
            long retentionHours,
            List<String> zOrderColumns,
            String predicate,
            String inventoryTable,
            boolean automatic,
            boolean approvalRequired) {
        public OperationConfig {
            zOrderColumns = List.copyOf(zOrderColumns == null ? List.of() : zOrderColumns);
            predicate = Objects.requireNonNullElse(predicate, "");
            inventoryTable = Objects.requireNonNullElse(inventoryTable, "");
        }
    }
}
