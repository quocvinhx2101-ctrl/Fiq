package io.fiq.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenancePolicyTest {
    @Test
    void selectorMatchesEachContextDimensionAndTags() {
        var selector =
                new MaintenancePolicy.Selector(
                        "prod*", "main", "analytics", "event?", Map.of("tier", "gold"));

        assertThat(selector.matches(DomainFixtures.TABLE, Map.of("tier", "gold"))).isTrue();
        assertThat(selector.matches(DomainFixtures.TABLE, Map.of("tier", "silver"))).isFalse();
    }
}
