package io.fiq.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TableIdentifier(
        UUID workspaceId,
        String environment,
        String catalog,
        List<String> namespace,
        String name,
        ExecutionTarget executionTarget) {

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
        executionTarget = Objects.requireNonNull(executionTarget, "executionTarget");
    }

    public String qualifiedName() {
        return String.join(".", catalog, String.join(".", namespace), name);
    }

    public java.util.Optional<String> location() {
        return executionTarget instanceof PathTarget path
                ? java.util.Optional.of(path.uri().toString())
                : java.util.Optional.empty();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
