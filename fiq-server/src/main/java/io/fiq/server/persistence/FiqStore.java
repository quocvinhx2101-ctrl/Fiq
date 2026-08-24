package io.fiq.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fiq.domain.DeltaCapabilities;
import io.fiq.domain.DeltaTableSnapshot;
import io.fiq.domain.HealthAssessment;
import io.fiq.domain.HealthCompleteness;
import io.fiq.domain.HealthDimension;
import io.fiq.domain.HealthDimensionMetadata;
import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationPlan;
import io.fiq.domain.OperationState;
import io.fiq.domain.OperationType;
import io.fiq.domain.Role;
import io.fiq.domain.TableAccessMode;
import io.fiq.domain.TableIdentifier;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.security.ApiKeyContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class FiqStore {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Map<String, Object>>> NESTED_OBJECT_MAP =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;

    public ApiModels.Workspace workspace(UUID workspaceId) {
        return queryOne(
                "SELECT id, name, timezone FROM workspaces WHERE id = ?",
                statement -> statement.setObject(1, workspaceId),
                result ->
                        new ApiModels.Workspace(
                                result.getObject("id", UUID.class),
                                result.getString("name"),
                                result.getString("timezone")),
                "FIQ_WORKSPACE_NOT_FOUND",
                "Workspace was not found");
    }

    public ApiModels.Environment defaultEnvironment(UUID workspaceId) {
        return queryOne(
                "SELECT id, name, production FROM environments WHERE workspace_id = ? "
                        + "ORDER BY production DESC, name LIMIT 1",
                statement -> statement.setObject(1, workspaceId),
                result ->
                        new ApiModels.Environment(
                                result.getObject("id", UUID.class),
                                result.getString("name"),
                                result.getBoolean("production")),
                "FIQ_ENVIRONMENT_NOT_FOUND",
                "Workspace has no environment");
    }

    public ApiModels.Overview overview(UUID workspaceId) {
        var sql =
                """
                SELECT
                  (SELECT COUNT(*) FROM delta_tables WHERE workspace_id = ?) AS tables,
                  (SELECT COUNT(*) FROM health_issues i JOIN health_assessments h ON h.id=i.assessment_id
                     WHERE h.workspace_id=? AND i.severity='WARNING') AS warnings,
                  (SELECT COUNT(*) FROM health_issues i JOIN health_assessments h ON h.id=i.assessment_id
                     WHERE h.workspace_id=? AND i.severity='CRITICAL') AS critical,
                  (SELECT COUNT(*) FROM operation_runs WHERE workspace_id=? AND state IN ('QUEUED','RUNNING','CANCELLING')) AS active,
                  (SELECT COUNT(*) FROM operation_runs WHERE workspace_id=? AND state='FAILED') AS failed,
                  (SELECT COUNT(*) FROM operation_runs WHERE workspace_id=? AND state='AWAITING_APPROVAL') AS approvals,
                  COALESCE((SELECT SUM((storage_retention->>'reclaimableBytes')::bigint)
                     FROM health_assessments WHERE workspace_id=?), 0) AS reclaimable
                """;
        return queryOne(
                sql,
                statement -> {
                    for (int index = 1; index <= 7; index++)
                        statement.setObject(index, workspaceId);
                },
                result ->
                        new ApiModels.Overview(
                                result.getLong("tables"),
                                result.getLong("warnings"),
                                result.getLong("critical"),
                                result.getLong("active"),
                                result.getLong("failed"),
                                result.getLong("approvals"),
                                result.getLong("reclaimable"),
                                Instant.now()),
                "FIQ_OVERVIEW_UNAVAILABLE",
                "Overview could not be calculated");
    }

    public ApiModels.Page<ApiModels.TableSummary> listTables(
            UUID workspaceId, String cursor, int requestedLimit, String search) {
        var limit = Math.max(1, Math.min(requestedLimit, 200));
        var normalizedCursor = cursor == null ? "" : cursor;
        var normalizedSearch = search == null ? "" : search.trim();
        var sql =
                """
                SELECT t.*,
                       COALESCE((h.file_layout->>'activeFileCount')::bigint,
                         (h.file_layout->>'fileCount')::bigint, 0) file_count,
                       COALESCE((h.file_layout->>'totalBytes')::bigint, 0) total_bytes,
                       COALESCE(h.debt_score, 0) debt_score,
                       COALESCE(h.completeness, 'STALE') health_completeness,
                       COALESCE((SELECT i.severity FROM health_issues i WHERE i.assessment_id=h.id
                         ORDER BY i.severity_rank DESC LIMIT 1), 'HEALTHY') severity
                FROM delta_tables t
                LEFT JOIN LATERAL (
                    SELECT * FROM health_assessments h0
                    WHERE h0.table_id=t.id ORDER BY assessed_at DESC LIMIT 1
                ) h ON true
                WHERE t.workspace_id=? AND t.qualified_name > ?
                  AND (? = '' OR t.qualified_name ILIKE '%' || ? || '%')
                ORDER BY t.qualified_name
                LIMIT ?
                """;
        var items =
                queryList(
                        sql,
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setString(2, normalizedCursor);
                            statement.setString(3, normalizedSearch);
                            statement.setString(4, normalizedSearch);
                            statement.setInt(5, limit + 1);
                        },
                        this::tableSummary);
        var hasNext = items.size() > limit;
        if (hasNext) items = new ArrayList<>(items.subList(0, limit));
        var next = hasNext ? items.get(items.size() - 1).qualifiedName() : null;
        return new ApiModels.Page<>(items, next);
    }

    public ApiModels.TableDetail tableDetail(UUID workspaceId, UUID tableId) {
        try (var row = tableRow(workspaceId, tableId)) {
            var summary = tableSummary(row.result());
            var snapshot = snapshot(row.result());
            var capabilityViews =
                    new EnumMap<OperationType, ApiModels.CapabilityView>(OperationType.class);
            for (var entry : new DeltaCapabilities().evaluate(snapshot).entrySet()) {
                var value = entry.getValue();
                capabilityViews.put(
                        entry.getKey(),
                        new ApiModels.CapabilityView(
                                value.supported(), value.approvalRequired(), value.reason()));
            }
            return new ApiModels.TableDetail(
                    summary,
                    row.result().getString("location_uri"),
                    readStringList(row.result().getString("namespace_parts")),
                    readStringList(row.result().getString("partition_columns")),
                    readStringList(row.result().getString("clustering_columns")),
                    readStringMap(row.result().getString("properties")),
                    readStringMap(row.result().getString("tags")),
                    Map.copyOf(capabilityViews));
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    public DeltaTableSnapshot loadSnapshot(UUID workspaceId, UUID tableId) {
        try (var row = tableRow(workspaceId, tableId)) {
            return snapshot(row.result());
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    public Map<String, String> tableConnectionOptions(UUID workspaceId, UUID tableId) {
        return queryOne(
                """
                SELECT c.options FROM delta_tables t
                JOIN connections c ON c.id=t.connection_id
                WHERE t.workspace_id=? AND t.id=?
                """,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, tableId);
                },
                result -> readStringMap(result.getString("options")),
                "FIQ_TABLE_NOT_FOUND",
                "Delta table was not found");
    }

    public ApiModels.ConnectionView tableConnection(UUID workspaceId, UUID tableId) {
        return queryOne(
                """
                SELECT c.* FROM delta_tables t
                JOIN connections c ON c.id=t.connection_id
                WHERE t.workspace_id=? AND t.id=?
                """,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, tableId);
                },
                this::connectionView,
                "FIQ_TABLE_NOT_FOUND",
                "Delta table was not found");
    }

    public Map<String, String> loadTableTags(UUID workspaceId, UUID tableId) {
        return queryOne(
                "SELECT tags FROM delta_tables WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, tableId);
                },
                result -> readStringMap(result.getString("tags")),
                "FIQ_TABLE_NOT_FOUND",
                "Delta table was not found");
    }

    public boolean tableSample(UUID workspaceId, UUID tableId) {
        return queryOne(
                "SELECT sample FROM delta_tables WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, tableId);
                },
                result -> result.getBoolean(1),
                "FIQ_TABLE_NOT_FOUND",
                "Delta table was not found");
    }

    public ApiModels.HealthView latestHealthView(UUID workspaceId, UUID tableId) {
        var sql =
                """
                SELECT h.*,
                  COALESCE((SELECT jsonb_object_agg(d.dimension, jsonb_build_object(
                    'dimension', d.dimension, 'completeness', d.completeness,
                    'provenance', d.provenance, 'observedAt', d.observed_at,
                    'observedVersion', d.observed_version,
                    'incompleteReason', d.incomplete_reason, 'facts', d.facts))
                    FROM assessment_dimensions d WHERE d.assessment_id=h.id), '{}') dimensions,
                  COALESCE((SELECT jsonb_agg(jsonb_build_object(
                    'code', i.code, 'severity', i.severity, 'dimension', i.dimension,
                    'summary', i.summary, 'recommendation', i.recommendation))
                    FROM health_issues i WHERE i.assessment_id=h.id), '[]') issues
                FROM health_assessments h
                WHERE h.workspace_id=? AND h.table_id=?
                ORDER BY h.assessed_at DESC LIMIT 1
                """;
        return queryOne(
                sql,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, tableId);
                },
                result ->
                        new ApiModels.HealthView(
                                result.getObject("id", UUID.class),
                                result.getObject("table_id", UUID.class),
                                result.getLong("observed_version"),
                                instant(result, "assessed_at"),
                                result.getString("provenance"),
                                HealthCompleteness.valueOf(result.getString("completeness")),
                                result.getString("stale_reason"),
                                readNestedObjectMap(result.getString("dimensions")),
                                readObjectMap(result.getString("file_layout")),
                                readObjectMap(result.getString("deletion_vectors")),
                                readObjectMap(result.getString("transaction_log")),
                                readObjectMap(result.getString("storage_retention")),
                                readObjectMap(result.getString("clustering")),
                                readObjectMap(result.getString("protocol")),
                                result.getDouble("debt_score"),
                                readObjectList(result.getString("issues"))),
                "FIQ_HEALTH_NOT_FOUND",
                "No health assessment is available for the table");
    }

    public HealthAssessment loadHealth(UUID workspaceId, UUID tableId, TableIdentifier table) {
        var view = latestHealthView(workspaceId, tableId);
        try {
            var dimensions =
                    new EnumMap<HealthDimension, HealthDimensionMetadata>(HealthDimension.class);
            for (var entry : view.dimensions().entrySet()) {
                var value = new java.util.LinkedHashMap<>(entry.getValue());
                value.remove("facts");
                dimensions.put(
                        HealthDimension.valueOf(entry.getKey()),
                        mapper.convertValue(value, HealthDimensionMetadata.class));
            }
            if (dimensions.isEmpty()) {
                for (var dimension : HealthDimension.values()) {
                    dimensions.put(
                            dimension,
                            new HealthDimensionMetadata(
                                    dimension,
                                    view.completeness(),
                                    view.provenance(),
                                    view.assessedAt(),
                                    view.observedVersion(),
                                    Optional.ofNullable(view.staleReason())));
                }
            }
            return new HealthAssessment(
                    table,
                    view.observedVersion(),
                    view.assessedAt(),
                    dimensions,
                    mapper.convertValue(view.fileLayout(), HealthAssessment.FileLayout.class),
                    mapper.convertValue(
                            view.deletionVectors(), HealthAssessment.DeletionVectors.class),
                    mapper.convertValue(
                            view.transactionLog(), HealthAssessment.TransactionLog.class),
                    mapper.convertValue(
                            view.storageRetention(), HealthAssessment.StorageRetention.class),
                    mapper.convertValue(view.clustering(), HealthAssessment.Clustering.class),
                    mapper.convertValue(view.protocol(), HealthAssessment.Protocol.class),
                    view.issues().stream()
                            .map(
                                    value ->
                                            mapper.convertValue(
                                                    value, io.fiq.domain.HealthIssue.class))
                            .toList());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    Response.Status.INTERNAL_SERVER_ERROR,
                    "FIQ_HEALTH_CORRUPT",
                    "The stored health assessment is invalid");
        }
    }

    public ApiModels.HealthView saveHealth(
            UUID workspaceId, UUID tableId, HealthAssessment health, double debtScore) {
        var assessmentId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var assessment =
                            connection.prepareStatement(
                                    """
                            INSERT INTO health_assessments(id, workspace_id, table_id, observed_version,
                              assessed_at, provenance, completeness, stale_reason, file_layout,
                              deletion_vectors, transaction_log, storage_retention, clustering,
                              protocol, debt_score)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                              ?::jsonb, ?::jsonb, ?::jsonb, ?)
                            """);
                    var issue =
                            connection.prepareStatement(
                                    """
                            INSERT INTO health_issues(assessment_id, code, severity, dimension,
                              summary, recommendation) VALUES (?, ?, ?, ?, ?, ?)
                            """);
                    var dimension =
                            connection.prepareStatement(
                                    """
                            INSERT INTO assessment_dimensions(assessment_id, dimension,
                              completeness, provenance, observed_at, observed_version,
                              incomplete_reason, facts)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """);
                    var fileDistribution =
                            connection.prepareStatement(
                                    """
                            INSERT INTO file_size_distributions(assessment_id, bucket_width_bytes,
                              bucket_counts, overflow_count, median_value_bytes,
                              median_error_bound_bytes, median_method)
                            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
                            """);
                    var tombstoneDistribution =
                            connection.prepareStatement(
                                    """
                            INSERT INTO tombstone_age_distributions(assessment_id,
                              bucket_upper_hours, bucket_counts)
                            VALUES (?, ?::jsonb, ?::jsonb)
                            """);
                    var table =
                            connection.prepareStatement(
                                    """
                            UPDATE delta_tables SET current_version=?, partition_columns=?::jsonb,
                              clustering_columns=?::jsonb, min_reader_version=?,
                              min_writer_version=?, table_features=?::jsonb, refreshed_at=NOW()
                            WHERE workspace_id=? AND id=?
                            """)) {
                assessment.setObject(1, assessmentId);
                assessment.setObject(2, workspaceId);
                assessment.setObject(3, tableId);
                assessment.setLong(4, health.observedVersion());
                assessment.setObject(
                        5,
                        java.time.OffsetDateTime.ofInstant(
                                health.assessedAt(), java.time.ZoneOffset.UTC));
                assessment.setString(6, health.provenance());
                assessment.setString(7, health.completeness().name());
                assessment.setString(8, health.staleReason().orElse(null));
                assessment.setString(9, json(health.fileLayout()));
                assessment.setString(10, json(health.deletionVectors()));
                assessment.setString(11, json(health.transactionLog()));
                assessment.setString(12, json(health.storageRetention()));
                assessment.setString(13, json(health.clustering()));
                assessment.setString(14, json(health.protocol()));
                assessment.setDouble(15, debtScore);
                assessment.executeUpdate();
                for (var entry : health.dimensions().entrySet()) {
                    var metadata = entry.getValue();
                    dimension.setObject(1, assessmentId);
                    dimension.setString(2, entry.getKey().name());
                    dimension.setString(3, metadata.completeness().name());
                    dimension.setString(4, metadata.provenance());
                    dimension.setObject(
                            5,
                            java.time.OffsetDateTime.ofInstant(
                                    metadata.observedAt(), java.time.ZoneOffset.UTC));
                    dimension.setLong(6, metadata.observedVersion());
                    dimension.setString(7, metadata.incompleteReason().orElse(null));
                    dimension.setString(8, json(dimensionFacts(health, entry.getKey())));
                    dimension.addBatch();
                }
                dimension.executeBatch();
                var histogram = health.fileLayout().fileSizeHistogram();
                var median = health.fileLayout().medianFileBytes();
                fileDistribution.setObject(1, assessmentId);
                fileDistribution.setLong(2, histogram.bucketWidthBytes());
                fileDistribution.setString(3, json(histogram.bucketCounts()));
                fileDistribution.setLong(4, histogram.overflowCount());
                fileDistribution.setLong(5, median.valueBytes());
                fileDistribution.setLong(6, median.errorBoundBytes());
                fileDistribution.setString(7, median.method());
                fileDistribution.executeUpdate();
                if (!health.storageRetention().tombstoneAgeBucketCounts().isEmpty()) {
                    tombstoneDistribution.setObject(1, assessmentId);
                    tombstoneDistribution.setString(
                            2, json(health.storageRetention().tombstoneAgeBucketHours()));
                    tombstoneDistribution.setString(
                            3, json(health.storageRetention().tombstoneAgeBucketCounts()));
                    tombstoneDistribution.executeUpdate();
                }
                for (var value : health.issues()) {
                    issue.setObject(1, assessmentId);
                    issue.setString(2, value.code());
                    issue.setString(3, value.severity().name());
                    issue.setString(4, value.dimension());
                    issue.setString(5, value.summary());
                    issue.setString(6, value.recommendation());
                    issue.addBatch();
                }
                issue.executeBatch();
                table.setLong(1, health.observedVersion());
                table.setString(2, json(health.clustering().partitionColumns()));
                table.setString(3, json(health.clustering().clusteringColumns()));
                table.setInt(4, health.protocol().minReaderVersion());
                table.setInt(5, health.protocol().minWriterVersion());
                table.setString(6, json(health.protocol().tableFeatures()));
                table.setObject(7, workspaceId);
                table.setObject(8, tableId);
                if (table.executeUpdate() != 1)
                    throw new SQLException("table disappeared during health refresh");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        return latestHealthView(workspaceId, tableId);
    }

    public ApiModels.Page<ApiModels.PolicySummary> listPolicies(UUID workspaceId, int limit) {
        var items =
                queryList(
                        "SELECT * FROM policies WHERE workspace_id=? ORDER BY name LIMIT ?",
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
                        },
                        result ->
                                new ApiModels.PolicySummary(
                                        result.getObject("id", UUID.class),
                                        result.getString("name"),
                                        result.getString("description"),
                                        result.getBoolean("enabled"),
                                        result.getString("cron_expression"),
                                        result.getString("timezone"),
                                        readOperationTypes(result.getString("enabled_operations")),
                                        result.getLong("max_bytes_per_run"),
                                        result.getInt("max_concurrent_operations"),
                                        instant(result, "updated_at")));
        return new ApiModels.Page<>(items, null);
    }

    public MaintenancePolicy loadPolicy(UUID workspaceId, UUID policyId) {
        return queryOne(
                "SELECT * FROM policies WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, policyId);
                },
                result -> {
                    var selectorMap = readObjectMap(result.getString("selector"));
                    var windowMap = readObjectMap(result.getString("maintenance_window"));
                    var operationMap = readObjectMap(result.getString("operation_configs"));
                    var configs =
                            new EnumMap<OperationType, MaintenancePolicy.OperationConfig>(
                                    OperationType.class);
                    for (var entry : operationMap.entrySet()) {
                        configs.put(
                                OperationType.valueOf(entry.getKey()),
                                mapper.convertValue(
                                        entry.getValue(), MaintenancePolicy.OperationConfig.class));
                    }
                    return new MaintenancePolicy(
                            result.getObject("id", UUID.class),
                            workspaceId,
                            result.getString("name"),
                            result.getBoolean("enabled"),
                            new MaintenancePolicy.Selector(
                                    string(selectorMap, "environmentGlob", "*"),
                                    string(selectorMap, "catalogGlob", "*"),
                                    string(selectorMap, "namespaceGlob", "*"),
                                    string(selectorMap, "tableGlob", "*"),
                                    mapper.convertValue(
                                            selectorMap.getOrDefault("requiredTags", Map.of()),
                                            STRING_MAP)),
                            result.getString("cron_expression"),
                            ZoneId.of(result.getString("timezone")),
                            new MaintenancePolicy.MaintenanceWindow(
                                    parseDays(windowMap.get("days")),
                                    LocalTime.parse(string(windowMap, "start", "00:00")),
                                    LocalTime.parse(string(windowMap, "end", "23:59:59"))),
                            readOperationTypes(result.getString("enabled_operations")),
                            configs,
                            result.getLong("max_bytes_per_run"),
                            result.getInt("max_concurrent_operations"),
                            result.getBoolean("require_approval_above_budget"));
                },
                "FIQ_POLICY_NOT_FOUND",
                "Maintenance policy was not found");
    }

    public ApiModels.PolicySummary savePolicy(
            UUID workspaceId, UUID policyId, ApiModels.PolicyRequest request) {
        var id = policyId == null ? UUID.randomUUID() : policyId;
        var operationNames = request.operations().stream().map(Enum::name).sorted().toList();
        if (policyId == null) {
            execute(
                    """
                    INSERT INTO policies(id, workspace_id, name, description, enabled, selector,
                      cron_expression, timezone, maintenance_window, enabled_operations,
                      operation_configs, max_bytes_per_run, max_concurrent_operations,
                      require_approval_above_budget)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """,
                    statement ->
                            bindPolicy(statement, 1, id, workspaceId, request, operationNames));
        } else {
            var updated =
                    execute(
                            """
                            UPDATE policies SET name=?, description=?, enabled=?, selector=?::jsonb,
                              cron_expression=?, timezone=?, maintenance_window=?::jsonb,
                              enabled_operations=?::jsonb, operation_configs=?::jsonb,
                              max_bytes_per_run=?, max_concurrent_operations=?,
                              require_approval_above_budget=?, updated_at=NOW()
                            WHERE workspace_id=? AND id=?
                            """,
                            statement -> {
                                statement.setString(1, request.name());
                                statement.setString(2, request.description());
                                statement.setBoolean(3, request.enabled());
                                statement.setString(4, json(request.selector()));
                                statement.setString(5, request.cron());
                                statement.setString(6, request.timezone());
                                statement.setString(7, json(request.maintenanceWindow()));
                                statement.setString(8, json(operationNames));
                                statement.setString(9, json(request.operationConfigs()));
                                statement.setLong(10, request.maxBytesPerRun());
                                statement.setInt(11, request.maxConcurrentOperations());
                                statement.setBoolean(12, request.requireApprovalAboveBudget());
                                statement.setObject(13, workspaceId);
                                statement.setObject(14, id);
                            });
            if (updated != 1) {
                throw new ApiException(
                        Response.Status.NOT_FOUND,
                        "FIQ_POLICY_NOT_FOUND",
                        "Maintenance policy was not found");
            }
        }
        return policySummary(workspaceId, id);
    }

    public ApiModels.PolicySummary policySummary(UUID workspaceId, UUID policyId) {
        return queryOne(
                "SELECT * FROM policies WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, policyId);
                },
                result ->
                        new ApiModels.PolicySummary(
                                result.getObject("id", UUID.class),
                                result.getString("name"),
                                result.getString("description"),
                                result.getBoolean("enabled"),
                                result.getString("cron_expression"),
                                result.getString("timezone"),
                                readOperationTypes(result.getString("enabled_operations")),
                                result.getLong("max_bytes_per_run"),
                                result.getInt("max_concurrent_operations"),
                                instant(result, "updated_at")),
                "FIQ_POLICY_NOT_FOUND",
                "Maintenance policy was not found");
    }

    public void deletePolicy(UUID workspaceId, UUID policyId) {
        var deleted =
                execute(
                        "DELETE FROM policies WHERE workspace_id=? AND id=?",
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setObject(2, policyId);
                        });
        if (deleted != 1) {
            throw new ApiException(
                    Response.Status.NOT_FOUND,
                    "FIQ_POLICY_NOT_FOUND",
                    "Maintenance policy was not found");
        }
    }

    public List<ApiModels.PolicySimulationMatch> simulatePolicy(
            UUID workspaceId, MaintenancePolicy policy) {
        return queryList(
                        "SELECT * FROM delta_tables WHERE workspace_id=? ORDER BY qualified_name",
                        statement -> statement.setObject(1, workspaceId),
                        result -> {
                            var snapshot = snapshot(result);
                            if (!policy.selector()
                                    .matches(
                                            snapshot.table(),
                                            readStringMap(result.getString("tags")))) return null;
                            var capabilities = new DeltaCapabilities().evaluate(snapshot);
                            var available = EnumSet.noneOf(OperationType.class);
                            var blocked = new EnumMap<OperationType, String>(OperationType.class);
                            for (var operation : policy.enabledOperations()) {
                                var capability = capabilities.get(operation);
                                if (capability.supported()) available.add(operation);
                                else blocked.put(operation, capability.reason());
                            }
                            return new ApiModels.PolicySimulationMatch(
                                    result.getObject("id", UUID.class),
                                    result.getString("qualified_name"),
                                    snapshot.accessMode(),
                                    available,
                                    blocked);
                        })
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<PolicyReference> enabledPolicyReferences() {
        return queryList(
                "SELECT workspace_id, id FROM policies WHERE enabled=true ORDER BY workspace_id, id",
                statement -> {},
                result ->
                        new PolicyReference(
                                result.getObject("workspace_id", UUID.class),
                                result.getObject("id", UUID.class)));
    }

    public List<UUID> matchingActiveTables(UUID workspaceId, MaintenancePolicy policy) {
        return queryList(
                        """
                        SELECT id FROM delta_tables
                        WHERE workspace_id=? AND discovery_status='ACTIVE'
                        ORDER BY id
                        """,
                        statement -> statement.setObject(1, workspaceId),
                        result -> result.getObject("id", UUID.class))
                .stream()
                .filter(
                        tableId ->
                                policy.selector()
                                        .matches(
                                                loadSnapshot(workspaceId, tableId).table(),
                                                loadTableTags(workspaceId, tableId)))
                .toList();
    }

    public long activePolicyOperationCount(UUID workspaceId, UUID policyId) {
        return queryOne(
                """
                SELECT COUNT(*) count FROM operation_runs
                WHERE workspace_id=? AND policy_id=?
                  AND state IN ('QUEUED','RUNNING','CANCELLING')
                """,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, policyId);
                },
                result -> result.getLong("count"),
                "FIQ_POLICY_NOT_FOUND",
                "Policy was not found");
    }

    public boolean claimPolicyFire(
            UUID policyId,
            UUID tableId,
            OperationType operation,
            Instant scheduledTimeUtc,
            String fireKey,
            String owner,
            int leaseSeconds) {
        var sql =
                """
                INSERT INTO policy_schedule_state(policy_id, table_id, operation_type,
                  scheduled_time_utc, fire_key, state, claim_owner, claim_expires_at)
                VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, NOW() + (? * INTERVAL '1 second'))
                ON CONFLICT (fire_key) DO UPDATE SET state='CLAIMED', claim_owner=EXCLUDED.claim_owner,
                  claim_expires_at=EXCLUDED.claim_expires_at, updated_at=NOW(), error_message=NULL
                WHERE policy_schedule_state.state='CLAIMED'
                  AND policy_schedule_state.claim_expires_at < NOW()
                RETURNING fire_key
                """;
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, policyId);
            statement.setObject(2, tableId);
            statement.setString(3, operation.name());
            statement.setObject(
                    4,
                    java.time.OffsetDateTime.ofInstant(scheduledTimeUtc, java.time.ZoneOffset.UTC));
            statement.setString(5, fireKey);
            statement.setString(6, owner);
            statement.setInt(7, leaseSeconds);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    public void completePolicyFire(
            String fireKey, String state, UUID operationId, String errorMessage) {
        execute(
                """
                UPDATE policy_schedule_state SET state=?, operation_id=?, error_message=?,
                  claim_owner=NULL, claim_expires_at=NULL, updated_at=NOW()
                WHERE fire_key=? AND state='CLAIMED'
                """,
                statement -> {
                    statement.setString(1, state);
                    statement.setObject(2, operationId);
                    statement.setString(3, errorMessage);
                    statement.setString(4, fireKey);
                });
    }

    private void bindPolicy(
            PreparedStatement statement,
            int offset,
            UUID id,
            UUID workspaceId,
            ApiModels.PolicyRequest request,
            List<String> operationNames)
            throws SQLException {
        statement.setObject(offset, id);
        statement.setObject(offset + 1, workspaceId);
        statement.setString(offset + 2, request.name());
        statement.setString(offset + 3, request.description());
        statement.setBoolean(offset + 4, request.enabled());
        statement.setString(offset + 5, json(request.selector()));
        statement.setString(offset + 6, request.cron());
        statement.setString(offset + 7, request.timezone());
        statement.setString(offset + 8, json(request.maintenanceWindow()));
        statement.setString(offset + 9, json(operationNames));
        statement.setString(offset + 10, json(request.operationConfigs()));
        statement.setLong(offset + 11, request.maxBytesPerRun());
        statement.setInt(offset + 12, request.maxConcurrentOperations());
        statement.setBoolean(offset + 13, request.requireApprovalAboveBudget());
    }

    public ApiModels.OperationView savePlan(
            UUID workspaceId,
            UUID tableId,
            UUID policyId,
            OperationPlan plan,
            String idempotencyKey) {
        var initialState =
                plan.executable() && plan.approvalRequired()
                        ? OperationState.AWAITING_APPROVAL
                        : OperationState.PLANNED;
        var sql =
                """
                INSERT INTO operation_runs(
                  id, workspace_id, table_id, policy_id, operation_type, state,
                  based_on_version, estimated_bytes, command_preview, reasons, warnings,
                  approval_required, idempotency_key, execution_target_snapshot,
                  planning_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (workspace_id, idempotency_key) DO NOTHING
                """;
        var inserted =
                execute(
                        sql,
                        statement -> {
                            statement.setObject(1, plan.id());
                            statement.setObject(2, workspaceId);
                            statement.setObject(3, tableId);
                            statement.setObject(4, policyId);
                            statement.setString(5, plan.operationType().name());
                            statement.setString(6, initialState.name());
                            statement.setLong(7, plan.basedOnVersion());
                            statement.setLong(8, plan.estimatedBytes());
                            statement.setString(9, plan.commandPreview());
                            statement.setString(10, json(plan.reasons()));
                            statement.setString(11, json(plan.warnings()));
                            statement.setBoolean(12, plan.approvalRequired());
                            statement.setString(13, idempotencyKey);
                            statement.setString(14, json(plan.table().executionTarget()));
                            statement.setString(15, json(plan.evaluation()));
                        });
        if (inserted == 1) {
            execute(
                    """
                    INSERT INTO policy_evaluations(workspace_id, policy_id, table_id,
                      assessment_id, operation_id, operation_type, decision, observations,
                      policy_thresholds, conditions, blockers, evaluated_at)
                    SELECT ?, ?, ?, h.id, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?
                    FROM health_assessments h
                    WHERE h.workspace_id=? AND h.table_id=? AND h.observed_version=?
                    ORDER BY h.assessed_at DESC LIMIT 1
                    """,
                    statement -> {
                        var evaluation = plan.evaluation();
                        statement.setObject(1, workspaceId);
                        statement.setObject(2, policyId);
                        statement.setObject(3, tableId);
                        statement.setObject(4, plan.id());
                        statement.setString(5, plan.operationType().name());
                        statement.setString(6, evaluation.decision().name());
                        statement.setString(7, json(evaluation.observations()));
                        statement.setString(8, json(evaluation.policyThresholds()));
                        statement.setString(9, json(evaluation.conditions()));
                        statement.setString(10, json(evaluation.blockers()));
                        statement.setObject(
                                11,
                                java.time.OffsetDateTime.ofInstant(
                                        evaluation.evaluatedAt(), java.time.ZoneOffset.UTC));
                        statement.setObject(12, workspaceId);
                        statement.setObject(13, tableId);
                        statement.setLong(14, plan.basedOnVersion());
                    });
            var steps = List.of("ASSESS", "PREFLIGHT", "SUBMIT", "EXECUTE", "VERIFY");
            for (var index = 0; index < steps.size(); index++) {
                var stepIndex = index;
                execute(
                        """
                        INSERT INTO operation_steps(operation_id, step_order, name, state,
                          evidence, started_at, completed_at)
                        VALUES (?, ?, ?, ?, ?::jsonb,
                          CASE WHEN ?='SUCCEEDED' THEN NOW() END,
                          CASE WHEN ?='SUCCEEDED' THEN NOW() END)
                        """,
                        statement -> {
                            var state = stepIndex < 2 ? "SUCCEEDED" : "PENDING";
                            var evidence =
                                    stepIndex == 0
                                            ? Map.of("observedVersion", plan.basedOnVersion())
                                            : stepIndex == 1
                                                    ? plan.evaluation().observations()
                                                    : Map.of();
                            statement.setObject(1, plan.id());
                            statement.setInt(2, stepIndex);
                            statement.setString(3, steps.get(stepIndex));
                            statement.setString(4, state);
                            statement.setString(5, json(evidence));
                            statement.setString(6, state);
                            statement.setString(7, state);
                        });
            }
        }
        return operationByIdempotencyKey(workspaceId, idempotencyKey);
    }

    public ApiModels.OperationView operation(UUID workspaceId, UUID operationId) {
        return operationQuery(
                "o.workspace_id=? AND o.id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, operationId);
                });
    }

    public Optional<ApiModels.OperationView> findOperationByIdempotencyKey(
            UUID workspaceId, String key) {
        return queryList(
                        operationSelect() + " WHERE o.workspace_id=? AND o.idempotency_key=?",
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setString(2, key);
                        },
                        this::operationView)
                .stream()
                .findFirst();
    }

    public void attachPreflight(
            UUID workspaceId, UUID operationId, Map<String, Object> evidence, String evidenceHash) {
        var updated =
                execute(
                        """
                        UPDATE operation_runs SET preflight_evidence=?::jsonb,
                          approval_evidence_hash=?, updated_at=NOW()
                        WHERE workspace_id=? AND id=? AND state IN ('PLANNED','AWAITING_APPROVAL')
                        """,
                        statement -> {
                            statement.setString(1, json(evidence));
                            statement.setString(2, evidenceHash);
                            statement.setObject(3, workspaceId);
                            statement.setObject(4, operationId);
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_OPERATION_CHANGED",
                    "Operation changed before preflight evidence was attached");
        }
    }

    public void updateOperationStep(
            UUID workspaceId,
            UUID operationId,
            String name,
            String state,
            Map<String, Object> evidence) {
        var updated =
                execute(
                        """
                        UPDATE operation_steps s SET state=?, evidence=?::jsonb,
                          started_at=CASE WHEN ?='RUNNING' THEN COALESCE(started_at, NOW())
                            ELSE started_at END,
                          completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED')
                            THEN NOW() ELSE completed_at END
                        FROM operation_runs o
                        WHERE s.operation_id=o.id AND o.workspace_id=? AND o.id=? AND s.name=?
                        """,
                        statement -> {
                            statement.setString(1, state);
                            statement.setString(2, json(evidence));
                            statement.setString(3, state);
                            statement.setString(4, state);
                            statement.setObject(5, workspaceId);
                            statement.setObject(6, operationId);
                            statement.setString(7, name);
                        });
        if (updated != 1) throw new IllegalStateException("Operation step was not found: " + name);
    }

    public ApiModels.Page<ApiModels.OperationView> listOperations(UUID workspaceId, int limit) {
        var sql = operationSelect() + " WHERE o.workspace_id=? ORDER BY o.planned_at DESC LIMIT ?";
        var operations =
                queryList(
                        sql,
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
                        },
                        this::operationView);
        return new ApiModels.Page<>(operations, null);
    }

    public ApiModels.OperationView transition(
            UUID workspaceId,
            UUID operationId,
            OperationState expected,
            OperationState target,
            String externalJobId,
            String errorCode,
            String errorMessage) {
        if (!expected.canTransitionTo(target)) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_INVALID_OPERATION_TRANSITION",
                    "Operation cannot transition from " + expected + " to " + target);
        }
        var sql =
                """
                UPDATE operation_runs SET state=?, external_job_id=COALESCE(?, external_job_id),
                  error_code=?, error_message=?, updated_at=NOW(),
                  queued_at=CASE WHEN ?='QUEUED' THEN NOW() ELSE queued_at END,
                  started_at=CASE WHEN ?='RUNNING' THEN NOW() ELSE started_at END,
                  completed_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED') THEN NOW() ELSE completed_at END
                WHERE workspace_id=? AND id=? AND state=?
                """;
        var updated =
                execute(
                        sql,
                        statement -> {
                            statement.setString(1, target.name());
                            statement.setString(2, externalJobId);
                            statement.setString(3, errorCode);
                            statement.setString(4, errorMessage);
                            statement.setString(5, target.name());
                            statement.setString(6, target.name());
                            statement.setString(7, target.name());
                            statement.setObject(8, workspaceId);
                            statement.setObject(9, operationId);
                            statement.setString(10, expected.name());
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_OPERATION_CHANGED",
                    "Operation state changed concurrently; refresh and retry");
        }
        return operation(workspaceId, operationId);
    }

    public ApiModels.OperationView complete(
            UUID workspaceId,
            UUID operationId,
            OperationState target,
            Map<String, Object> evidence,
            String errorCode,
            String errorMessage) {
        if (!OperationState.RUNNING.canTransitionTo(target) || !target.isTerminal()) {
            throw new IllegalArgumentException("Target must be a terminal RUNNING transition");
        }
        var updated =
                execute(
                        """
                        UPDATE operation_runs SET state=?, result=?::jsonb, error_code=?, error_message=?,
                          completed_at=NOW(), lease_owner=NULL, lease_expires_at=NULL, updated_at=NOW()
                        WHERE workspace_id=? AND id=? AND state='RUNNING'
                        """,
                        statement -> {
                            statement.setString(1, target.name());
                            statement.setString(2, json(evidence));
                            statement.setString(3, errorCode);
                            statement.setString(4, errorMessage);
                            statement.setObject(5, workspaceId);
                            statement.setObject(6, operationId);
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_OPERATION_CHANGED",
                    "Operation state changed concurrently; refresh and retry");
        }
        return operation(workspaceId, operationId);
    }

    public ApiModels.OperationView completeVerified(
            UUID workspaceId,
            UUID operationId,
            OperationState target,
            Map<String, Object> result,
            Map<String, Object> verification,
            boolean maintenanceApplied,
            String resultUri,
            String checksum,
            String errorCode,
            String errorMessage) {
        if (!OperationState.RUNNING.canTransitionTo(target) || !target.isTerminal()) {
            throw new IllegalArgumentException("Target must be a terminal RUNNING transition");
        }
        var updated =
                execute(
                        """
                        UPDATE operation_runs SET state=?, result=?::jsonb,
                          verification_evidence=?::jsonb, maintenance_applied=?,
                          structured_result_uri=?, structured_result_checksum=?, error_code=?,
                          error_message=?, completed_at=NOW(), lease_owner=NULL,
                          lease_expires_at=NULL, updated_at=NOW()
                        WHERE workspace_id=? AND id=? AND state='RUNNING'
                        """,
                        statement -> {
                            statement.setString(1, target.name());
                            statement.setString(2, json(result));
                            statement.setString(3, json(verification));
                            statement.setBoolean(4, maintenanceApplied);
                            statement.setString(5, resultUri);
                            statement.setString(6, checksum);
                            statement.setString(7, errorCode);
                            statement.setString(8, errorMessage);
                            statement.setObject(9, workspaceId);
                            statement.setObject(10, operationId);
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_OPERATION_CHANGED",
                    "Operation state changed concurrently; refresh and retry");
        }
        return operation(workspaceId, operationId);
    }

    public ApiModels.OperationView approve(
            UUID workspaceId, UUID operationId, String principal, String decision, String comment) {
        var operation = operation(workspaceId, operationId);
        if (operation.state() != OperationState.AWAITING_APPROVAL) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_APPROVAL_NOT_EXPECTED",
                    "Operation is not awaiting approval");
        }
        var approved = "APPROVE".equalsIgnoreCase(decision);
        var target = approved ? OperationState.QUEUED : OperationState.CANCELLED;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var approval =
                            connection.prepareStatement(
                                    """
                                    INSERT INTO approvals(workspace_id, operation_id, decision,
                                      principal, comment, evidence_hash, evidence)
                                    SELECT workspace_id, id, ?, ?, ?, approval_evidence_hash,
                                      jsonb_build_object('planning', planning_evidence,
                                        'preflight', preflight_evidence,
                                        'executionTarget', execution_target_snapshot,
                                        'basedOnVersion', based_on_version)
                                    FROM operation_runs WHERE workspace_id=? AND id=?
                                    """);
                    var update =
                            connection.prepareStatement(
                                    "UPDATE operation_runs SET state=?, approved_by=?, approved_at=NOW(), updated_at=NOW() "
                                            + "WHERE workspace_id=? AND id=? AND state='AWAITING_APPROVAL'")) {
                approval.setString(1, approved ? "APPROVED" : "REJECTED");
                approval.setString(2, principal);
                approval.setString(3, comment);
                approval.setObject(4, workspaceId);
                approval.setObject(5, operationId);
                approval.executeUpdate();
                update.setString(1, target.name());
                update.setString(2, principal);
                update.setObject(3, workspaceId);
                update.setObject(4, operationId);
                if (update.executeUpdate() != 1) throw new SQLException("concurrent approval");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        return operation(workspaceId, operationId);
    }

    public List<ApiModels.ConnectionView> listConnections(UUID workspaceId) {
        return queryList(
                "SELECT * FROM connections WHERE workspace_id=? ORDER BY name",
                statement -> statement.setObject(1, workspaceId),
                this::connectionView);
    }

    public ApiModels.ConnectionView createConnection(
            UUID workspaceId, ApiModels.ConnectionRequest request) {
        validateSecretRef(request.secretRef());
        var id = UUID.randomUUID();
        execute(
                """
                INSERT INTO connections(id, workspace_id, environment_id, name, catalog_type,
                  catalog_uri, warehouse_uri, engine_type, engine_uri, secret_ref, options)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                statement -> {
                    statement.setObject(1, id);
                    statement.setObject(2, workspaceId);
                    statement.setObject(3, request.environmentId());
                    statement.setString(4, request.name());
                    statement.setString(5, request.catalogType());
                    statement.setString(6, request.catalogUri());
                    statement.setString(7, request.warehouseUri());
                    statement.setString(8, request.engineType());
                    statement.setString(9, request.engineUri());
                    statement.setString(10, request.secretRef());
                    statement.setString(
                            11, json(request.options() == null ? Map.of() : request.options()));
                });
        return connection(workspaceId, id);
    }

    public ApiModels.ConnectionView ensureSampleConnection(
            UUID id,
            UUID workspaceId,
            UUID environmentId,
            String name,
            String catalogType,
            String catalogUri,
            String warehouseUri,
            String engineUri,
            Map<String, String> options) {
        execute(
                """
                INSERT INTO connections(id, workspace_id, environment_id, name, catalog_type,
                  catalog_uri, warehouse_uri, engine_type, engine_uri, options)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'LIVY', ?, ?::jsonb)
                ON CONFLICT (workspace_id, name) DO UPDATE SET
                  catalog_type=EXCLUDED.catalog_type, catalog_uri=EXCLUDED.catalog_uri,
                  warehouse_uri=EXCLUDED.warehouse_uri, engine_uri=EXCLUDED.engine_uri,
                  options=EXCLUDED.options, enabled=true, updated_at=NOW()
                """,
                statement -> {
                    statement.setObject(1, id);
                    statement.setObject(2, workspaceId);
                    statement.setObject(3, environmentId);
                    statement.setString(4, name);
                    statement.setString(5, catalogType);
                    statement.setString(6, catalogUri);
                    statement.setString(7, warehouseUri);
                    statement.setString(8, engineUri);
                    statement.setString(9, json(options));
                });
        return queryOne(
                "SELECT * FROM connections WHERE workspace_id=? AND name=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setString(2, name);
                },
                this::connectionView,
                "FIQ_SAMPLE_CONNECTION_FAILED",
                "Sample connection was not created");
    }

    public ApiModels.ConnectionView connection(UUID workspaceId, UUID connectionId) {
        return queryOne(
                "SELECT * FROM connections WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, connectionId);
                },
                this::connectionView,
                "FIQ_CONNECTION_NOT_FOUND",
                "Connection was not found");
    }

    public ApiModels.DiscoveryRunView createDiscoveryRun(
            UUID workspaceId,
            UUID connectionId,
            String rootUri,
            int maxDepth,
            int maxTables,
            int timeoutSeconds) {
        var id = UUID.randomUUID();
        execute(
                """
                INSERT INTO discovery_runs(
                  id, workspace_id, connection_id, state, root_uri,
                  max_depth, max_tables, timeout_seconds)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?)
                """,
                statement -> {
                    statement.setObject(1, id);
                    statement.setObject(2, workspaceId);
                    statement.setObject(3, connectionId);
                    statement.setString(4, rootUri);
                    statement.setInt(5, maxDepth);
                    statement.setInt(6, maxTables);
                    statement.setInt(7, timeoutSeconds);
                });
        return discoveryRun(workspaceId, id);
    }

    public ApiModels.DiscoveryRunView discoveryRun(UUID workspaceId, UUID runId) {
        return queryOne(
                "SELECT * FROM discovery_runs WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, runId);
                },
                this::discoveryRunView,
                "FIQ_DISCOVERY_RUN_NOT_FOUND",
                "Discovery run was not found");
    }

    public void upsertPathTable(
            UUID workspaceId,
            ApiModels.ConnectionView connection,
            String catalogAlias,
            String namespace,
            io.fiq.delta.DeltaPathDiscovery.DiscoveredPathTable discovered,
            boolean sample) {
        var target = discovered.target();
        var metrics = discovered.metrics();
        var path = target.uri().getPath();
        var tableName = path.substring(path.lastIndexOf('/') + 1);
        var targetJson = json(Map.of("type", "PATH", "uri", target.uri().toString()));
        execute(
                """
                INSERT INTO delta_tables(
                  workspace_id, environment_id, connection_id, catalog_name, namespace_parts,
                  table_name, qualified_name, location_uri, access_mode, current_version,
                  min_reader_version, min_writer_version, table_features, partition_columns,
                  clustering_columns, properties, tags, source_type, execution_target_type,
                  execution_target, execution_target_fingerprint, display_identity,
                  discovery_status, sample, last_seen_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'CLASSIC', ?, 1, 2, '[]', ?::jsonb,
                  ?::jsonb, ?::jsonb, ?::jsonb, 'PATH', 'PATH', ?::jsonb,
                  encode(digest(?::jsonb::text, 'sha256'), 'hex'), ?, 'ACTIVE', ?, NOW())
                ON CONFLICT (workspace_id, connection_id, execution_target_fingerprint)
                DO UPDATE SET current_version=EXCLUDED.current_version,
                  partition_columns=EXCLUDED.partition_columns,
                  clustering_columns=EXCLUDED.clustering_columns,
                  properties=EXCLUDED.properties, tags=EXCLUDED.tags,
                  discovery_status='ACTIVE', sample=EXCLUDED.sample,
                  last_seen_at=NOW(), missing_since=NULL, refreshed_at=NOW()
                """,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, connection.environmentId());
                    statement.setObject(3, connection.id());
                    statement.setString(4, catalogAlias);
                    statement.setString(5, json(List.of(namespace)));
                    statement.setString(6, tableName);
                    statement.setString(7, catalogAlias + "." + namespace + "." + tableName);
                    statement.setString(8, target.uri().toString());
                    statement.setLong(9, metrics.version());
                    statement.setString(10, json(metrics.partitionColumns()));
                    statement.setString(11, json(metrics.clusteringColumns()));
                    statement.setString(12, json(sample ? Map.of("fiq.sample", "true") : Map.of()));
                    statement.setString(13, json(sample ? Map.of("sample", "true") : Map.of()));
                    statement.setString(14, targetJson);
                    statement.setString(15, targetJson);
                    statement.setString(16, target.uri().toString());
                    statement.setBoolean(17, sample);
                });
    }

    public void upsertCatalogTable(
            UUID workspaceId,
            ApiModels.ConnectionView connection,
            String catalog,
            List<String> namespace,
            String tableName,
            String location,
            long version,
            int minReaderVersion,
            int minWriterVersion,
            List<String> features,
            List<String> partitionColumns,
            Map<String, String> properties,
            boolean sample) {
        var targetJson =
                json(
                        Map.of(
                                "type", "CATALOG",
                                "catalog", catalog,
                                "namespace", namespace,
                                "table", tableName));
        var qualified = String.join(".", catalog, String.join(".", namespace), tableName);
        execute(
                """
                INSERT INTO delta_tables(
                  workspace_id, environment_id, connection_id, catalog_name, namespace_parts,
                  table_name, qualified_name, location_uri, access_mode, current_version,
                  min_reader_version, min_writer_version, table_features, partition_columns,
                  clustering_columns, properties, tags, source_type, execution_target_type,
                  execution_target, execution_target_fingerprint, display_identity,
                  discovery_status, sample, last_seen_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'CLASSIC', ?, ?, ?, ?::jsonb, ?::jsonb,
                  '[]', ?::jsonb, ?::jsonb, 'HMS', 'CATALOG', ?::jsonb,
                  encode(digest(?::jsonb::text, 'sha256'), 'hex'), ?, 'ACTIVE', ?, NOW())
                ON CONFLICT (workspace_id, connection_id, execution_target_fingerprint)
                DO UPDATE SET location_uri=EXCLUDED.location_uri,
                  current_version=EXCLUDED.current_version,
                  min_reader_version=EXCLUDED.min_reader_version,
                  min_writer_version=EXCLUDED.min_writer_version,
                  table_features=EXCLUDED.table_features,
                  partition_columns=EXCLUDED.partition_columns,
                  properties=EXCLUDED.properties, tags=EXCLUDED.tags,
                  discovery_status='ACTIVE', sample=EXCLUDED.sample,
                  last_seen_at=NOW(), missing_since=NULL, refreshed_at=NOW()
                """,
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, connection.environmentId());
                    statement.setObject(3, connection.id());
                    statement.setString(4, catalog);
                    statement.setString(5, json(namespace));
                    statement.setString(6, tableName);
                    statement.setString(7, qualified);
                    statement.setString(8, location);
                    statement.setLong(9, version);
                    statement.setInt(10, minReaderVersion);
                    statement.setInt(11, minWriterVersion);
                    statement.setString(12, json(features));
                    statement.setString(13, json(partitionColumns));
                    statement.setString(14, json(properties));
                    statement.setString(15, json(sample ? Map.of("sample", "true") : Map.of()));
                    statement.setString(16, targetJson);
                    statement.setString(17, targetJson);
                    statement.setString(18, qualified);
                    statement.setBoolean(19, sample);
                });
    }

    public ApiModels.DiscoveryRunView completeDiscovery(
            UUID workspaceId, UUID runId, int tablesFound) {
        var missing =
                execute(
                        """
                        UPDATE delta_tables SET discovery_status='MISSING', missing_since=NOW()
                        WHERE workspace_id=?
                          AND connection_id=(SELECT connection_id FROM discovery_runs WHERE id=? AND workspace_id=?)
                          AND discovery_status='ACTIVE'
                          AND last_seen_at < (SELECT started_at FROM discovery_runs WHERE id=? AND workspace_id=?)
                        """,
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setObject(2, runId);
                            statement.setObject(3, workspaceId);
                            statement.setObject(4, runId);
                            statement.setObject(5, workspaceId);
                        });
        execute(
                """
                UPDATE discovery_runs SET state='SUCCEEDED', tables_found=?, tables_missing=?,
                  completed_at=NOW() WHERE workspace_id=? AND id=? AND state='RUNNING'
                """,
                statement -> {
                    statement.setInt(1, tablesFound);
                    statement.setInt(2, missing);
                    statement.setObject(3, workspaceId);
                    statement.setObject(4, runId);
                });
        return discoveryRun(workspaceId, runId);
    }

    public ApiModels.DiscoveryRunView failDiscovery(
            UUID workspaceId, UUID runId, String code, String message) {
        execute(
                """
                UPDATE discovery_runs SET state='FAILED', error_code=?, error_message=?,
                  completed_at=NOW() WHERE workspace_id=? AND id=? AND state='RUNNING'
                """,
                statement -> {
                    statement.setString(1, code);
                    statement.setString(2, message);
                    statement.setObject(3, workspaceId);
                    statement.setObject(4, runId);
                });
        return discoveryRun(workspaceId, runId);
    }

    public void recordConnectionTest(
            UUID workspaceId, UUID connectionId, String status, String message) {
        var updated =
                execute(
                        """
                UPDATE connections SET last_tested_at=NOW(), last_test_status=?,
                  last_test_message=?, updated_at=NOW() WHERE workspace_id=? AND id=?
                """,
                        statement -> {
                            statement.setString(1, status);
                            statement.setString(2, message);
                            statement.setObject(3, workspaceId);
                            statement.setObject(4, connectionId);
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.NOT_FOUND,
                    "FIQ_CONNECTION_NOT_FOUND",
                    "Connection was not found");
        }
    }

    public Optional<ApiKeyContext.Authentication> authenticateApiKey(String rawKey) {
        var matches =
                queryList(
                        """
                        SELECT id, workspace_id, name, roles FROM api_keys
                        WHERE workspace_id IS NOT NULL AND enabled=true
                          AND (expires_at IS NULL OR expires_at > NOW())
                          AND key_hash=encode(digest(?, 'sha256'), 'hex')
                        LIMIT 1
                        """,
                        statement -> statement.setString(1, rawKey),
                        result -> {
                            var roles = EnumSet.noneOf(Role.class);
                            for (var role : readStringList(result.getString("roles"))) {
                                roles.add(Role.valueOf(role));
                            }
                            return new ApiKeyContext.Authentication(
                                    result.getObject("id", UUID.class),
                                    result.getObject("workspace_id", UUID.class),
                                    "api-key:" + result.getString("name"),
                                    roles);
                        });
        if (matches.isEmpty()) return Optional.empty();
        var authentication = matches.getFirst();
        execute(
                "UPDATE api_keys SET last_used_at=NOW() WHERE id=?",
                statement -> statement.setObject(1, authentication.keyId()));
        return Optional.of(authentication);
    }

    public List<ApiModels.ApiKeyView> listApiKeys(UUID workspaceId) {
        return queryList(
                "SELECT * FROM api_keys WHERE workspace_id=? ORDER BY created_at DESC",
                statement -> statement.setObject(1, workspaceId),
                this::apiKeyView);
    }

    public ApiModels.ApiKeyView createApiKey(
            UUID workspaceId, ApiModels.ApiKeyRequest request, String secret) {
        var id = UUID.randomUUID();
        execute(
                """
                INSERT INTO api_keys(id, workspace_id, name, key_prefix, key_hash, roles, expires_at)
                VALUES (?, ?, ?, ?, encode(digest(?, 'sha256'), 'hex'), ?::jsonb, ?)
                """,
                statement -> {
                    statement.setObject(1, id);
                    statement.setObject(2, workspaceId);
                    statement.setString(3, request.name());
                    statement.setString(4, secret.substring(0, Math.min(secret.length(), 16)));
                    statement.setString(5, secret);
                    statement.setString(
                            6, json(request.roles().stream().map(Enum::name).sorted().toList()));
                    statement.setObject(
                            7,
                            request.expiresAt() == null
                                    ? null
                                    : java.time.OffsetDateTime.ofInstant(
                                            request.expiresAt(), java.time.ZoneOffset.UTC));
                });
        return queryOne(
                "SELECT * FROM api_keys WHERE workspace_id=? AND id=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setObject(2, id);
                },
                this::apiKeyView,
                "FIQ_API_KEY_NOT_FOUND",
                "API key was not found");
    }

    public void revokeApiKey(UUID workspaceId, UUID keyId) {
        var updated =
                execute(
                        "UPDATE api_keys SET enabled=false WHERE workspace_id=? AND id=?",
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setObject(2, keyId);
                        });
        if (updated != 1) {
            throw new ApiException(
                    Response.Status.NOT_FOUND, "FIQ_API_KEY_NOT_FOUND", "API key was not found");
        }
    }

    public ApiModels.Page<ApiModels.AuditEvent> auditEvents(UUID workspaceId, int limit) {
        var events =
                queryList(
                        "SELECT * FROM audit_events WHERE workspace_id=? ORDER BY occurred_at DESC LIMIT ?",
                        statement -> {
                            statement.setObject(1, workspaceId);
                            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
                        },
                        result ->
                                new ApiModels.AuditEvent(
                                        result.getLong("id"),
                                        instant(result, "occurred_at"),
                                        result.getString("event_type"),
                                        result.getString("severity"),
                                        result.getString("principal"),
                                        result.getString("resource_type"),
                                        result.getString("resource_id"),
                                        readObjectMap(result.getString("details"))));
        return new ApiModels.Page<>(events, null);
    }

    public void audit(
            UUID workspaceId,
            String eventType,
            String severity,
            String principal,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {
        execute(
                "INSERT INTO audit_events(workspace_id, event_type, severity, principal, resource_type, resource_id, details) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setString(2, eventType);
                    statement.setString(3, severity);
                    statement.setString(4, principal);
                    statement.setString(5, resourceType);
                    statement.setString(6, resourceId);
                    statement.setString(7, json(details));
                });
    }

    public List<ApiModels.OperationView> claimQueuedOperations(
            String owner, int batchSize, int leaseSeconds) {
        var claimed = new ArrayList<ApiModels.OperationView>();
        var sql =
                """
                WITH candidates AS (
                  SELECT id FROM operation_runs
                  WHERE state='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
                    AND (lease_expires_at IS NULL OR lease_expires_at < NOW())
                  ORDER BY queued_at NULLS LAST, planned_at
                  FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE operation_runs o SET lease_owner=?,
                  lease_expires_at=NOW() + (? * INTERVAL '1 second'), updated_at=NOW()
                FROM candidates c WHERE o.id=c.id RETURNING o.workspace_id, o.id
                """;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setInt(1, batchSize);
                statement.setString(2, owner);
                statement.setInt(3, leaseSeconds);
                try (var result = statement.executeQuery()) {
                    var ids = new ArrayList<Map.Entry<UUID, UUID>>();
                    while (result.next()) {
                        ids.add(
                                Map.entry(
                                        result.getObject("workspace_id", UUID.class),
                                        result.getObject("id", UUID.class)));
                    }
                    connection.commit();
                    for (var id : ids) claimed.add(operation(id.getKey(), id.getValue()));
                }
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        return claimed;
    }

    public List<ApiModels.OperationView> claimActiveOperations(
            String owner, int batchSize, int leaseSeconds) {
        var ids = new ArrayList<Map.Entry<UUID, UUID>>();
        var sql =
                """
                WITH candidates AS (
                  SELECT id FROM operation_runs
                  WHERE state IN ('RUNNING','CANCELLING') AND external_job_id IS NOT NULL
                    AND (lease_expires_at IS NULL OR lease_expires_at < NOW() OR lease_owner = ?)
                  ORDER BY started_at NULLS LAST
                  FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE operation_runs o SET lease_owner=?,
                  lease_expires_at=NOW() + (? * INTERVAL '1 second'), updated_at=NOW()
                FROM candidates c WHERE o.id=c.id RETURNING o.workspace_id, o.id
                """;
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setInt(2, batchSize);
            statement.setString(3, owner);
            statement.setInt(4, leaseSeconds);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(
                            Map.entry(
                                    result.getObject("workspace_id", UUID.class),
                                    result.getObject("id", UUID.class)));
                }
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
        return ids.stream().map(id -> operation(id.getKey(), id.getValue())).toList();
    }

    public ApiModels.OperationView retrySubmission(
            UUID workspaceId, UUID operationId, String errorMessage) {
        execute(
                """
                UPDATE operation_runs SET
                  attempt=attempt+1,
                  state=CASE WHEN attempt+1 >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                  next_attempt_at=CASE WHEN attempt+1 >= max_attempts THEN NULL
                    ELSE NOW() + ((30 * POWER(2, attempt)) * INTERVAL '1 second') END,
                  error_code='FIQ_SPARK_SUBMISSION_FAILED', error_message=?,
                  lease_owner=NULL, lease_expires_at=NULL,
                  completed_at=CASE WHEN attempt+1 >= max_attempts THEN NOW() ELSE NULL END,
                  updated_at=NOW()
                WHERE workspace_id=? AND id=? AND state='QUEUED'
                """,
                statement -> {
                    statement.setString(1, errorMessage);
                    statement.setObject(2, workspaceId);
                    statement.setObject(3, operationId);
                });
        return operation(workspaceId, operationId);
    }

    private ApiModels.OperationView operationByIdempotencyKey(UUID workspaceId, String key) {
        return operationQuery(
                "o.workspace_id=? AND o.idempotency_key=?",
                statement -> {
                    statement.setObject(1, workspaceId);
                    statement.setString(2, key);
                });
    }

    private ApiModels.OperationView operationQuery(String where, Binder binder) {
        return queryOne(
                operationSelect() + " WHERE " + where,
                binder,
                this::operationView,
                "FIQ_OPERATION_NOT_FOUND",
                "Operation was not found");
    }

    private String operationSelect() {
        return """
                SELECT o.*, t.qualified_name table_qualified_name,
                  o.execution_target_snapshot->>'type' table_target_type,
                  o.execution_target_snapshot table_execution_target,
                  o.planning_evidence policy_evaluation,
                  COALESCE((SELECT jsonb_agg(jsonb_build_object(
                    'name', s.name, 'state', s.state, 'evidence', s.evidence,
                    'startedAt', s.started_at, 'completedAt', s.completed_at)
                    ORDER BY s.step_order) FROM operation_steps s
                    WHERE s.operation_id=o.id), '[]') operation_steps
                FROM operation_runs o JOIN delta_tables t ON t.id=o.table_id
                """;
    }

    private ApiModels.OperationView operationView(ResultSet result) throws SQLException {
        return new ApiModels.OperationView(
                result.getObject("id", UUID.class),
                result.getObject("workspace_id", UUID.class),
                result.getObject("table_id", UUID.class),
                result.getString("table_qualified_name"),
                executionTarget(result, "table_"),
                result.getObject("policy_id", UUID.class),
                OperationType.valueOf(result.getString("operation_type")),
                OperationState.valueOf(result.getString("state")),
                result.getLong("based_on_version"),
                result.getLong("estimated_bytes"),
                result.getBoolean("approval_required"),
                result.getString("approved_by"),
                result.getString("external_job_id"),
                result.getString("command_preview"),
                readStringList(result.getString("reasons")),
                readStringList(result.getString("warnings")),
                readObjectMap(result.getString("policy_evaluation")),
                readObjectMap(result.getString("preflight_evidence")),
                readObjectMap(result.getString("result")),
                readObjectMap(result.getString("verification_evidence")),
                readObjectList(result.getString("operation_steps")),
                result.getBoolean("maintenance_applied"),
                result.getString("structured_result_uri"),
                result.getString("structured_result_checksum"),
                result.getString("error_code"),
                result.getString("error_message"),
                instant(result, "planned_at"),
                instantNullable(result, "started_at"),
                instantNullable(result, "completed_at"));
    }

    private ApiModels.TableSummary tableSummary(ResultSet result) throws SQLException {
        return new ApiModels.TableSummary(
                result.getObject("id", UUID.class),
                result.getString("qualified_name"),
                executionTarget(result, ""),
                result.getBoolean("sample"),
                result.getString("discovery_status"),
                environmentName(result.getObject("environment_id", UUID.class)),
                result.getString("catalog_name"),
                TableAccessMode.valueOf(result.getString("access_mode")),
                result.getLong("current_version"),
                getLong(result, "file_count", 0),
                getLong(result, "total_bytes", 0),
                getDouble(result, "debt_score", 0),
                getString(result, "severity", "HEALTHY"),
                HealthCompleteness.valueOf(getString(result, "health_completeness", "STALE")),
                instant(result, "refreshed_at"),
                Set.copyOf(readStringList(result.getString("table_features"))),
                result.getString("read_only_reason"));
    }

    private String environmentName(UUID environmentId) {
        return queryOne(
                "SELECT name FROM environments WHERE id=?",
                statement -> statement.setObject(1, environmentId),
                result -> result.getString(1),
                "FIQ_ENVIRONMENT_NOT_FOUND",
                "Environment was not found");
    }

    private DeltaTableSnapshot snapshot(ResultSet result) throws SQLException {
        var workspaceId = result.getObject("workspace_id", UUID.class);
        var environment = environmentName(result.getObject("environment_id", UUID.class));
        var identifier =
                new TableIdentifier(
                        workspaceId,
                        environment,
                        result.getString("catalog_name"),
                        readStringList(result.getString("namespace_parts")),
                        result.getString("table_name"),
                        Optional.ofNullable(result.getString("location_uri")),
                        executionTarget(result, ""));
        return new DeltaTableSnapshot(
                identifier,
                TableAccessMode.valueOf(result.getString("access_mode")),
                result.getLong("current_version"),
                instant(result, "refreshed_at"),
                result.getInt("min_reader_version"),
                result.getInt("min_writer_version"),
                Set.copyOf(readStringList(result.getString("table_features"))),
                readStringList(result.getString("partition_columns")),
                readStringList(result.getString("clustering_columns")),
                readStringMap(result.getString("properties")),
                result.getBoolean("catalog_maintenance_allowed"),
                result.getBoolean("filesystem_visible_state_current"));
    }

    private io.fiq.domain.ExecutionTarget executionTarget(ResultSet result, String prefix)
            throws SQLException {
        var value = readObjectMap(result.getString(prefix + "execution_target"));
        var type = result.getString(prefix + "target_type");
        if (type == null && prefix.isEmpty()) type = result.getString("execution_target_type");
        return switch (type) {
            case "PATH" -> io.fiq.domain.PathTarget.of(String.valueOf(value.get("uri")));
            case "CATALOG" ->
                    new io.fiq.domain.CatalogTarget(
                            String.valueOf(value.get("catalog")),
                            objectStringList(value.get("namespace")),
                            String.valueOf(value.get("table")));
            default -> throw new SQLException("Unknown execution target type: " + type);
        };
    }

    private static List<String> objectStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private RowHandle tableRow(UUID workspaceId, UUID tableId) {
        try {
            var connection = dataSource.getConnection();
            var statement =
                    connection.prepareStatement(
                            """
                    SELECT t.*,
                      COALESCE((h.file_layout->>'activeFileCount')::bigint,
                        (h.file_layout->>'fileCount')::bigint, 0) file_count,
                      COALESCE((h.file_layout->>'totalBytes')::bigint, 0) total_bytes,
                      COALESCE(h.debt_score, 0) debt_score,
                      COALESCE(h.completeness, 'STALE') health_completeness,
                      COALESCE((SELECT i.severity FROM health_issues i WHERE i.assessment_id=h.id
                        ORDER BY i.severity_rank DESC LIMIT 1), 'HEALTHY') severity
                    FROM delta_tables t
                    LEFT JOIN LATERAL (SELECT * FROM health_assessments h0 WHERE h0.table_id=t.id
                      ORDER BY assessed_at DESC LIMIT 1) h ON true
                    WHERE t.workspace_id=? AND t.id=?
                    """);
            statement.setObject(1, workspaceId);
            statement.setObject(2, tableId);
            var result = statement.executeQuery();
            if (!result.next()) {
                result.close();
                statement.close();
                connection.close();
                throw new ApiException(
                        Response.Status.NOT_FOUND,
                        "FIQ_TABLE_NOT_FOUND",
                        "Delta table was not found");
            }
            return new RowHandle(connection, statement, result);
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private ApiModels.ConnectionView connectionView(ResultSet result) throws SQLException {
        return new ApiModels.ConnectionView(
                result.getObject("id", UUID.class),
                result.getObject("environment_id", UUID.class),
                result.getString("name"),
                result.getString("catalog_type"),
                result.getString("catalog_uri"),
                result.getString("warehouse_uri"),
                result.getString("engine_type"),
                result.getString("engine_uri"),
                result.getString("secret_ref"),
                readStringMap(result.getString("options")),
                result.getBoolean("enabled"),
                instantNullable(result, "last_tested_at"),
                result.getString("last_test_status"),
                result.getString("last_test_message"));
    }

    private ApiModels.DiscoveryRunView discoveryRunView(ResultSet result) throws SQLException {
        return new ApiModels.DiscoveryRunView(
                result.getObject("id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getString("state"),
                result.getString("root_uri"),
                result.getInt("max_depth"),
                result.getInt("max_tables"),
                result.getInt("timeout_seconds"),
                result.getInt("tables_found"),
                result.getInt("tables_missing"),
                result.getString("error_code"),
                result.getString("error_message"),
                instant(result, "started_at"),
                instantNullable(result, "completed_at"));
    }

    private ApiModels.ApiKeyView apiKeyView(ResultSet result) throws SQLException {
        var roles = EnumSet.noneOf(Role.class);
        for (var role : readStringList(result.getString("roles"))) roles.add(Role.valueOf(role));
        return new ApiModels.ApiKeyView(
                result.getObject("id", UUID.class),
                result.getString("name"),
                result.getString("key_prefix"),
                roles,
                result.getBoolean("enabled"),
                instantNullable(result, "expires_at"),
                instantNullable(result, "last_used_at"),
                instant(result, "created_at"));
    }

    private static void validateSecretRef(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) return;
        var normalized = secretRef.toLowerCase();
        if (normalized.contains("password=")
                || normalized.contains("token=")
                || normalized.contains("secret=")) {
            throw new ApiException(
                    Response.Status.BAD_REQUEST,
                    "FIQ_SECRET_VALUE_FORBIDDEN",
                    "Connections accept only secret references, never credential values");
        }
    }

    private Set<OperationType> readOperationTypes(String value) {
        var result = EnumSet.noneOf(OperationType.class);
        for (var item : readStringList(value)) result.add(OperationType.valueOf(item));
        return result;
    }

    private Set<DayOfWeek> parseDays(Object value) {
        var result = EnumSet.noneOf(DayOfWeek.class);
        if (value instanceof List<?> days) {
            for (var day : days) result.add(DayOfWeek.valueOf(String.valueOf(day)));
        }
        if (result.isEmpty()) result = EnumSet.allOf(DayOfWeek.class);
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Value cannot be serialized", exception);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw invalidJson(exception);
        }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException exception) {
            throw invalidJson(exception);
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw invalidJson(exception);
        }
    }

    private Map<String, Map<String, Object>> readNestedObjectMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, NESTED_OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw invalidJson(exception);
        }
    }

    private static Object dimensionFacts(HealthAssessment health, HealthDimension dimension) {
        return switch (dimension) {
            case FILE_LAYOUT -> health.fileLayout();
            case DELETION_VECTORS -> health.deletionVectors();
            case TRANSACTION_LOG -> health.transactionLog();
            case RETENTION -> health.storageRetention();
            case CLUSTERING -> health.clustering();
            case PROTOCOL -> health.protocol();
        };
    }

    private List<Map<String, Object>> readObjectList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw invalidJson(exception);
        }
    }

    private static RuntimeException invalidJson(Exception exception) {
        return new ApiException(
                Response.Status.INTERNAL_SERVER_ERROR,
                "FIQ_PERSISTED_JSON_INVALID",
                "Persisted JSON does not match the FIQ contract: " + exception.getMessage());
    }

    private static String string(Map<String, Object> map, String key, String defaultValue) {
        var value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private <T> T queryOne(
            String sql,
            Binder binder,
            RowMapper<T> mapper,
            String missingCode,
            String missingMessage) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ApiException(Response.Status.NOT_FOUND, missingCode, missingMessage);
                }
                return mapper.map(result);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private <T> List<T> queryList(String sql, Binder binder, RowMapper<T> mapper) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var result = statement.executeQuery()) {
                var items = new ArrayList<T>();
                while (result.next()) items.add(mapper.map(result));
                return items;
            }
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private int execute(String sql, Binder binder) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistenceFailure(exception);
        }
    }

    private static ApiException persistenceFailure(SQLException exception) {
        return new ApiException(
                Response.Status.SERVICE_UNAVAILABLE,
                "FIQ_DATABASE_UNAVAILABLE",
                "The control-plane database operation failed: " + exception.getMessage());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, java.time.OffsetDateTime.class).toInstant();
    }

    private static Instant instantNullable(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static long getLong(ResultSet result, String column, long fallback) {
        try {
            return result.getLong(column);
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private static double getDouble(ResultSet result, String column, double fallback) {
        try {
            return result.getDouble(column);
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private static String getString(ResultSet result, String column, String fallback) {
        try {
            var value = result.getString(column);
            return value == null ? fallback : value;
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet result) throws SQLException;
    }

    private record RowHandle(Connection connection, PreparedStatement statement, ResultSet result)
            implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            result.close();
            statement.close();
            connection.close();
        }
    }

    public record PolicyReference(UUID workspaceId, UUID policyId) {}
}
