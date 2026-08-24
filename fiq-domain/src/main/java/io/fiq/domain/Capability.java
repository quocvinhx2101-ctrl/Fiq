package io.fiq.domain;

import java.util.Objects;

public record Capability(boolean supported, boolean approvalRequired, String reason) {
    public Capability {
        reason = Objects.requireNonNullElse(reason, "");
        if (!supported && reason.isBlank()) {
            throw new IllegalArgumentException("unsupported capability requires a reason");
        }
    }

    public static Capability supported(boolean approvalRequired) {
        return new Capability(true, approvalRequired, "");
    }

    public static Capability unsupported(String reason) {
        return new Capability(false, false, reason);
    }
}
