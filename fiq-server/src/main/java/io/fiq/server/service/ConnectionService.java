package io.fiq.server.service;

import io.fiq.delta.DeltaKernelInspector;
import io.fiq.domain.Role;
import io.fiq.engine.spark.SparkExecutionClient;
import io.fiq.server.api.ApiModels;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ConnectionService {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject SparkExecutionClient spark;

    private final DeltaKernelInspector kernel = new DeltaKernelInspector();

    public ApiModels.ConnectionTestResult test(UUID workspaceId, UUID connectionId) {
        access.require(Role.ADMIN);
        var connection = store.connection(workspaceId, connectionId);
        var capabilities = new LinkedHashMap<String, Object>();
        var engine = spark.probe();
        capabilities.put("livyReachable", engine.reachable());
        capabilities.put("maintenanceCommands", engine.maintenanceCommandsAvailable());
        capabilities.put("engineMessage", engine.message());
        var storageReachable = true;
        var message = engine.message();
        if ("PATH".equalsIgnoreCase(connection.catalogType())
                && connection.warehouseUri() != null
                && !connection.warehouseUri().isBlank()) {
            try {
                var metrics = kernel.inspect(connection.warehouseUri(), Map.of());
                capabilities.put("deltaVersion", metrics.version());
                capabilities.put("activeFiles", metrics.fileCount());
                capabilities.put("deletionVectors", metrics.filesWithDeletionVectors());
            } catch (RuntimeException exception) {
                storageReachable = false;
                message = "Delta path probe failed: " + exception.getMessage();
            }
        }
        var reachable = engine.reachable() && storageReachable;
        var status = reachable ? "HEALTHY" : "ERROR";
        store.recordConnectionTest(workspaceId, connectionId, status, message);
        store.audit(
                workspaceId,
                "CONNECTION_TESTED",
                reachable ? "INFO" : "WARN",
                access.principal(),
                "connection",
                connectionId.toString(),
                Map.of("status", status));
        return new ApiModels.ConnectionTestResult(
                reachable, status, message, Map.copyOf(capabilities));
    }
}
