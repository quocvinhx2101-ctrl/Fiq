package io.fiq.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SparkClientProviderTest {
    @Test
    void requiresAnEndpointOnlyWhenAConnectionUsesSpark() {
        var provider = new SparkClientProvider();
        provider.jobJar = "local:///opt/fiq/fiq-spark-job.jar";
        provider.mainClass = "io.fiq.spark.MaintenanceJob";

        assertThrows(IllegalArgumentException.class, () -> provider.forEndpoint(null));
        assertThrows(IllegalArgumentException.class, () -> provider.forEndpoint(" "));
        assertDoesNotThrow(() -> provider.forEndpoint("http://livy.example:8998"));
    }
}
