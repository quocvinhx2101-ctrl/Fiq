package io.fiq.engine.spark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LivyExecutionClient implements SparkExecutionClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final URI baseUri;
    private final String jobJar;
    private final String mainClass;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public LivyExecutionClient(URI baseUri, String jobJar, String mainClass) {
        this(
                baseUri,
                jobJar,
                mainClass,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper());
    }

    LivyExecutionClient(
            URI baseUri, String jobJar, String mainClass, HttpClient http, ObjectMapper mapper) {
        this.baseUri = normalizeBaseUri(baseUri);
        this.jobJar = requireText(jobJar, "jobJar");
        this.mainClass = requireText(mainClass, "mainClass");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String submit(SparkMaintenanceRequest request) {
        return submit(
                mainClass,
                "fiq-" + request.operationId(),
                arguments(request),
                request.sparkConf(),
                Map.of("spark.fiq.operationId", request.operationId().toString()));
    }

    @Override
    public String submitCatalogDiscovery(SparkCatalogDiscoveryRequest request) {
        return submit(
                "io.fiq.spark.CatalogDiscoveryJob",
                "fiq-discovery-" + request.runId(),
                List.of("--run-id", request.runId().toString(), "--catalog", request.catalog()),
                request.sparkConf(),
                Map.of("spark.fiq.discoveryRunId", request.runId().toString()));
    }

    @Override
    public String submitAssessment(SparkAssessmentRequest request) {
        var arguments = new ArrayList<String>();
        add(arguments, "--assessment-id", request.assessmentId().toString());
        addTarget(arguments, request.executionTarget());
        return submit(
                "io.fiq.spark.AssessmentJob",
                "fiq-assessment-" + request.assessmentId(),
                arguments,
                request.sparkConf(),
                Map.of("spark.fiq.assessmentId", request.assessmentId().toString()));
    }

    @Override
    public String submitVacuumPreflight(SparkVacuumPreflightRequest request) {
        var arguments = new ArrayList<String>();
        add(arguments, "--preflight-id", request.preflightId().toString());
        addTarget(arguments, request.executionTarget());
        add(arguments, "--expected-version", String.valueOf(request.expectedVersion()));
        add(arguments, "--retention-hours", String.valueOf(request.retentionHours()));
        add(arguments, "--result-prefix", request.resultPrefix());
        add(arguments, "--allow-unsafe-retention", String.valueOf(request.allowUnsafeRetention()));
        return submit(
                "io.fiq.spark.VacuumPreflightJob",
                "fiq-vacuum-preflight-" + request.preflightId(),
                arguments,
                request.sparkConf(),
                Map.of("spark.fiq.preflightId", request.preflightId().toString()));
    }

    private String submit(
            String className,
            String name,
            List<String> arguments,
            Map<String, String> requestedConf,
            Map<String, String> requiredConf) {
        var body = new LinkedHashMap<String, Object>();
        body.put("file", jobJar);
        body.put("className", className);
        body.put("name", name);
        body.put("args", arguments);
        var conf = new LinkedHashMap<>(requestedConf);
        conf.putAll(requiredConf);
        body.put("conf", conf);
        var response = send("POST", "/batches", body);
        var id = response.get("id");
        if (id == null) throw failure("Livy did not return a batch id");
        return String.valueOf(id);
    }

    @Override
    public SparkJobStatus status(String jobId) {
        var id = numericJobId(jobId);
        var response = send("GET", "/batches/" + id, null);
        var state = mapState(String.valueOf(response.getOrDefault("state", "unknown")));
        // The batch resource contains only Livy's short tail. Discovery emits a structured
        // marker before Spark shutdown, so retrieve the full bounded log through Livy's log API.
        var logResponse = send("GET", "/batches/" + id + "/log?from=0&size=10000", null);
        var log = stringList(logResponse.get("log"));
        return new SparkJobStatus(
                jobId,
                state,
                log,
                Map.of("livyState", response.getOrDefault("state", "unknown")),
                state == SparkJobState.FAILED ? String.join("\n", log) : null);
    }

    @Override
    public void cancel(String jobId) {
        send("DELETE", "/batches/" + numericJobId(jobId), null);
    }

    @Override
    public CapabilityProbeResult probe() {
        try {
            var response = send("GET", "/batches?from=0&size=1", null);
            return new CapabilityProbeResult(
                    true,
                    "Spark version is reported by submitted FIQ jobs",
                    "Delta version is reported by submitted FIQ jobs",
                    true,
                    "Livy endpoint is reachable");
        } catch (LivyException exception) {
            return new CapabilityProbeResult(false, null, null, false, exception.getMessage());
        }
    }

    private Map<String, Object> send(String method, String path, Object body) {
        try {
            var builder =
                    HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(30));
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(body)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(
                        "Livy returned HTTP " + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) return Map.of();
            return mapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException exception) {
            throw failure("Livy request failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("Livy request was interrupted", exception);
        }
    }

    private static List<String> arguments(SparkMaintenanceRequest request) {
        var args = new ArrayList<String>();
        add(args, "--operation-id", request.operationId().toString());
        add(args, "--operation", request.operationType().name());
        addTarget(args, request.executionTarget());
        add(args, "--expected-version", String.valueOf(request.expectedVersion()));
        add(args, "--retention-hours", String.valueOf(request.retentionHours()));
        if (!request.zOrderColumns().isEmpty()) {
            add(args, "--zorder-columns", String.join(",", request.zOrderColumns()));
        }
        if (!request.predicate().isBlank()) add(args, "--predicate", request.predicate());
        if (!request.inventoryTable().isBlank()) add(args, "--inventory", request.inventoryTable());
        if (!request.resultPrefix().isBlank()) add(args, "--result-prefix", request.resultPrefix());
        if (!request.approvedCandidateHash().isBlank()) {
            add(args, "--approved-candidate-hash", request.approvedCandidateHash());
            add(
                    args,
                    "--approved-candidate-count",
                    String.valueOf(request.approvedCandidateCount()));
        }
        add(args, "--allow-unsafe-retention", String.valueOf(request.allowUnsafeRetention()));
        return args;
    }

    private static void addTarget(List<String> args, io.fiq.domain.ExecutionTarget target) {
        switch (target) {
            case io.fiq.domain.PathTarget path -> {
                add(args, "--target-type", "PATH");
                add(args, "--path", path.uri().toString());
            }
            case io.fiq.domain.CatalogTarget catalog -> {
                add(args, "--target-type", "CATALOG");
                add(args, "--catalog", catalog.catalog());
                add(args, "--namespace", String.join(".", catalog.namespace()));
                add(args, "--table", catalog.table());
            }
        }
    }

    private static void add(List<String> args, String key, String value) {
        args.add(key);
        args.add(value);
    }

    private static SparkJobState mapState(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "starting", "not_started" -> SparkJobState.STARTING;
            case "running", "busy" -> SparkJobState.RUNNING;
            case "success" -> SparkJobState.SUCCEEDED;
            case "dead", "error" -> SparkJobState.FAILED;
            case "killed", "shutting_down" -> SparkJobState.CANCELLED;
            default -> SparkJobState.UNKNOWN;
        };
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String numericJobId(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Livy job id must be numeric");
        }
        return value;
    }

    private static URI normalizeBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        var text = value.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static LivyException failure(String message) {
        return new LivyException(message, null);
    }

    private static LivyException failure(String message, Throwable cause) {
        return new LivyException(message, cause);
    }

    public static final class LivyException extends RuntimeException {
        LivyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
