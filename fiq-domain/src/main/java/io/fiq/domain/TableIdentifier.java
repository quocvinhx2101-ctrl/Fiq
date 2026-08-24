package io.fiq.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TableIdentifier(
        UUID workspaceId,
        String environment,
        String catalog,
        List<String> namespace,
        String name,
        Optional<String> location) {

    public TableIdentifier {
        Objects.requireNonNull(workspaceId, "workspaceId");
        environment = requireText(environment, "environment");
        catalog = requireText(catalog, "catalog");
        namespace = List.copyOf(Objects.requireNonNull(namespace, "namespace"));
        if (namespace.isEmpty()
                || namespace.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("namespace must contain non-blank components");
        }
        name = requireText(name, "name");
        location = location == null ? Optional.empty() : location.filter(value -> !value.isBlank());
    }

    public String qualifiedName() {
        return String.join(".", catalog, String.join(".", namespace), name);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
