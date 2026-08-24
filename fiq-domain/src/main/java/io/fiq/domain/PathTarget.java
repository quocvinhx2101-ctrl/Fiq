package io.fiq.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record PathTarget(URI uri) implements ExecutionTarget {
    private static final Set<String> SUPPORTED_SCHEMES =
            Set.of("file", "hdfs", "s3", "s3a", "abfs", "abfss", "gs");

    public PathTarget {
        uri = canonicalize(Objects.requireNonNull(uri, "uri"));
    }

    @Override
    public String type() {
        return "PATH";
    }

    public static PathTarget of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PATH target URI is required");
        }
        return new PathTarget(URI.create(value));
    }

    private static URI canonicalize(URI value) {
        if (!value.isAbsolute() || value.getScheme() == null) {
            throw new IllegalArgumentException("PATH target must be an absolute URI");
        }
        var scheme = value.getScheme().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Unsupported PATH target scheme: " + scheme);
        }
        var allowedAbfsAuthority =
                Set.of("abfs", "abfss").contains(scheme)
                        && value.getUserInfo() != null
                        && !value.getUserInfo().contains(":");
        if ((value.getUserInfo() != null && !allowedAbfsAuthority)
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "PATH target must not contain credentials, a query, or a fragment");
        }
        var host = value.getHost() == null ? null : value.getHost().toLowerCase(Locale.ROOT);
        var path = value.getPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("PATH target URI must contain a path");
        }
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        try {
            return new URI(
                            scheme,
                            allowedAbfsAuthority ? value.getUserInfo() : null,
                            host,
                            value.getPort(),
                            path,
                            null,
                            null)
                    .normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid PATH target URI", exception);
        }
    }
}
