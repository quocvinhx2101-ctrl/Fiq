package io.fiq.domain;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

public record VacuumCandidateIdentity(List<String> canonicalUris, String sha256) {
    public VacuumCandidateIdentity {
        canonicalUris = List.copyOf(canonicalUris);
    }

    public static VacuumCandidateIdentity fromUris(Collection<String> uris) {
        var canonical =
                uris.stream()
                        .map(value -> URI.create(value).normalize().toASCIIString())
                        .distinct()
                        .sorted()
                        .toList();
        try {
            var bytes = String.join("\n", canonical).getBytes(StandardCharsets.UTF_8);
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new VacuumCandidateIdentity(canonical, hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
