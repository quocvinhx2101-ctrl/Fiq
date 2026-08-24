package io.fiq.delta;

import static org.assertj.core.api.Assertions.assertThat;

import io.fiq.domain.PathTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeltaPathDiscoveryTest {
    @Test
    void discoversAQualifiedDeltaRootWithoutDescendingIntoItsLog() throws Exception {
        var resource = getClass().getResource("/simple-table");
        var target = PathTarget.of(Path.of(resource.toURI()).toUri().toString());

        var discovered =
                new DeltaPathDiscovery().discover(target, 4, 10, Duration.ofSeconds(5), Map.of());

        assertThat(discovered).hasSize(1);
        assertThat(discovered.getFirst().target()).isEqualTo(target);
        assertThat(discovered.getFirst().metrics().fileCount()).isEqualTo(2);
    }

    @Test
    void rejectsUnboundedDiscoveryControls() throws Exception {
        var resource = getClass().getResource("/simple-table");
        var target = PathTarget.of(Path.of(resource.toURI()).toUri().toString());
        var discovery = new DeltaPathDiscovery();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> discovery.discover(target, 33, 10, Duration.ofSeconds(5), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> discovery.discover(target, 4, 0, Duration.ofSeconds(5), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
