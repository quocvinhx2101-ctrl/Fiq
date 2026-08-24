package io.fiq.domain;

public enum OperationType {
    OPTIMIZE_BINPACK,
    OPTIMIZE_ZORDER,
    OPTIMIZE_CLUSTERING,
    OPTIMIZE_FULL,
    REORG_PURGE,
    VACUUM_LITE,
    VACUUM_FULL,
    VACUUM_INVENTORY;

    public boolean isVacuum() {
        return this == VACUUM_LITE || this == VACUUM_FULL || this == VACUUM_INVENTORY;
    }

    public boolean rewritesData() {
        return switch (this) {
            case OPTIMIZE_BINPACK,
                    OPTIMIZE_ZORDER,
                    OPTIMIZE_CLUSTERING,
                    OPTIMIZE_FULL,
                    REORG_PURGE ->
                    true;
            case VACUUM_LITE, VACUUM_FULL, VACUUM_INVENTORY -> false;
        };
    }
}
