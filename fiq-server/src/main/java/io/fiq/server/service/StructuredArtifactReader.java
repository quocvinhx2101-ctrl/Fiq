package io.fiq.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class StructuredArtifactReader {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "fiq.s3.endpoint", defaultValue = "")
    String s3Endpoint;

    public Artifact read(String prefix, Map<String, String> connectionOptions) {
        try {
            var configuration = configuration(connectionOptions);
            var manifestPath = new Path(prefix.replaceAll("/+$", "") + "/manifest.json");
            var filesystem = manifestPath.getFileSystem(configuration);
            Map<String, Object> manifest;
            try (var input = filesystem.open(manifestPath)) {
                manifest = mapper.readValue(input, MAP);
            }
            var expectedResult = new Path(prefix.replaceAll("/+$", "") + "/result.json");
            var resultPath = new Path(String.valueOf(manifest.get("resultUri")));
            if (!filesystem
                    .makeQualified(expectedResult)
                    .equals(filesystem.makeQualified(resultPath))) {
                throw new IllegalStateException(
                        "Structured-result manifest points outside its run prefix");
            }
            byte[] bytes;
            try (var input = filesystem.open(resultPath)) {
                bytes = input.readAllBytes();
            }
            var actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var expected = String.valueOf(manifest.get("sha256"));
            if (!MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    expected.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException(
                        "Structured-result checksum does not match manifest");
            }
            return new Artifact(mapper.readValue(bytes, MAP), resultPath.toString(), actual);
        } catch (IOException exception) {
            throw new IllegalStateException("Structured result is unavailable", exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Configuration configuration(Map<String, String> connectionOptions) {
        var configuration = new Configuration(false);
        var options = new LinkedHashMap<String, String>();
        connectionOptions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("fs."))
                .forEach(entry -> options.put(entry.getKey(), entry.getValue()));
        if (!s3Endpoint.isBlank()) {
            options.put("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
            options.put("fs.s3a.endpoint", s3Endpoint);
            options.put("fs.s3a.path.style.access", "true");
            options.put("fs.s3a.connection.ssl.enabled", "false");
        }
        options.forEach(configuration::set);
        return configuration;
    }

    public record Artifact(Map<String, Object> result, String resultUri, String checksum) {
        public Artifact {
            result = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
        }
    }
}
