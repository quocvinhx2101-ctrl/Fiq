package io.fiq.domain;

public final class ExecutionTargetRenderer {
    private ExecutionTargetRenderer() {}

    public static String sql(ExecutionTarget target) {
        return switch (target) {
            case PathTarget path -> "delta.`" + escape(path.uri().toString()) + "`";
            case CatalogTarget catalog ->
                    String.join(
                            ".",
                            catalog.components().stream()
                                    .map(ExecutionTargetRenderer::quote)
                                    .toList());
        };
    }

    public static String display(ExecutionTarget target) {
        return switch (target) {
            case PathTarget path -> path.uri().toString();
            case CatalogTarget catalog -> String.join(".", catalog.components());
        };
    }

    private static String quote(String value) {
        return "`" + escape(value) + "`";
    }

    private static String escape(String value) {
        return value.replace("`", "``");
    }
}
