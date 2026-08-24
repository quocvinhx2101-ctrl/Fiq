package io.fiq.domain;

public sealed interface ExecutionTarget permits PathTarget, CatalogTarget {
    String type();
}
