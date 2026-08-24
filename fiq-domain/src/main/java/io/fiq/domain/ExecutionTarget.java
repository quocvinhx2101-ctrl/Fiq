package io.fiq.domain;

public sealed interface ExecutionTarget permits PathTarget, CatalogTarget {
    String type();

    /** JavaBean accessor keeps the discriminator explicit in REST and persisted evidence JSON. */
    default String getType() {
        return type();
    }
}
