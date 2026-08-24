package io.fiq.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CatalogTarget(String catalog, List<String> namespace, String table)
        implements ExecutionTarget {
    public CatalogTarget {
        catalog = requireComponent(catalog, "catalog");
        namespace = List.copyOf(Objects.requireNonNull(namespace, "namespace"));
        if (namespace.isEmpty()) throw new IllegalArgumentException("namespace must not be empty");
        namespace.forEach(value -> requireComponent(value, "namespace"));
        table = requireComponent(table, "table");
    }

    @Override
    public String type() {
        return "CATALOG";
    }

    public List<String> components() {
        var result = new ArrayList<String>();
        result.add(catalog);
        result.addAll(namespace);
        result.add(table);
        return List.copyOf(result);
    }

    private static String requireComponent(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }
}
