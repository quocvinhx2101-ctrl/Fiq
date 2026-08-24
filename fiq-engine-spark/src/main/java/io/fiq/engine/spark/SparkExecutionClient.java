package io.fiq.engine.spark;

public interface SparkExecutionClient {
    String submit(SparkMaintenanceRequest request);

    String submitCatalogDiscovery(SparkCatalogDiscoveryRequest request);

    String submitAssessment(SparkAssessmentRequest request);

    String submitVacuumPreflight(SparkVacuumPreflightRequest request);

    SparkJobStatus status(String jobId);

    void cancel(String jobId);

    CapabilityProbeResult probe();

    record CapabilityProbeResult(
            boolean reachable,
            String sparkVersion,
            String deltaVersion,
            boolean maintenanceCommandsAvailable,
            String message) {}
}
