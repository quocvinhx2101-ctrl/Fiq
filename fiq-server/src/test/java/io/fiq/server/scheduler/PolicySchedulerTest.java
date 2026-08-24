package io.fiq.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.fiq.domain.MaintenancePolicy;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicySchedulerTest {
    @Test
    void evaluatesOvernightMaintenanceWindowsInWorkspaceTimezone() {
        var window =
                new MaintenancePolicy.MaintenanceWindow(
                        Set.of(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(2, 0));
        var zone = ZoneId.of("Asia/Ho_Chi_Minh");

        assertThat(
                        PolicyScheduler.withinWindow(
                                window, ZonedDateTime.of(2026, 8, 24, 23, 0, 0, 0, zone)))
                .isTrue();
        assertThat(
                        PolicyScheduler.withinWindow(
                                window, ZonedDateTime.of(2026, 8, 25, 1, 0, 0, 0, zone)))
                .isTrue();
        assertThat(
                        PolicyScheduler.withinWindow(
                                window, ZonedDateTime.of(2026, 8, 25, 3, 0, 0, 0, zone)))
                .isFalse();
    }
}
