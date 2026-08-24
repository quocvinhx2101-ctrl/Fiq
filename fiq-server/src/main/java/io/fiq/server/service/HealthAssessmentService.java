package io.fiq.server.service;

import io.fiq.delta.DeltaKernelInspector;
import io.fiq.domain.HealthAssessment;
import io.fiq.domain.HealthCompleteness;
import io.fiq.domain.HealthIssue;
import io.fiq.domain.HealthSeverity;
import io.fiq.domain.Role;
import io.fiq.domain.TableAccessMode;
import io.fiq.server.api.ApiException;
import io.fiq.server.api.ApiModels;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class HealthAssessmentService {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject FiqEventBus events;

    private final DeltaKernelInspector inspector = new DeltaKernelInspector();

    public ApiModels.HealthView refresh(UUID workspaceId, UUID tableId) {
        access.require(Role.OPERATOR);
        return refresh(workspaceId, tableId, access.principal());
    }

    public ApiModels.HealthView refreshSystem(UUID workspaceId, UUID tableId) {
        return refresh(workspaceId, tableId, "fiq-scheduler");
    }

    private ApiModels.HealthView refresh(UUID workspaceId, UUID tableId, String principal) {
        var snapshot = store.loadSnapshot(workspaceId, tableId);
        if (snapshot.accessMode() == TableAccessMode.CATALOG_MANAGED) {
            throw new ApiException(
                    Response.Status.CONFLICT,
                    "FIQ_CATALOG_ASSESSMENT_REQUIRED",
                    "Catalog-managed tables must be assessed through their catalog-qualified Spark session");
        }
        var location =
                snapshot.table()
                        .location()
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                Response.Status.CONFLICT,
                                                "FIQ_TABLE_LOCATION_MISSING",
                                                "Classic Delta inspection requires a registered table location"));
        var metrics = inspector.inspect(location, Map.of());
        var issues =
                issues(
                        metrics.smallFileCount(),
                        metrics.smallFileRatio(),
                        metrics.filesWithDeletionVectors());
        var assessed =
                new HealthAssessment(
                        snapshot.table(),
                        metrics.version(),
                        Instant.now(),
                        "delta-kernel-4.0.1",
                        HealthCompleteness.PARTIAL,
                        Optional.of(
                                "Tombstones and log-retention coverage require the configured Spark metadata job"),
                        new HealthAssessment.FileLayout(
                                metrics.fileCount(),
                                metrics.totalBytes(),
                                metrics.minFileBytes(),
                                metrics.maxFileBytes(),
                                metrics.medianFileBytes(),
                                metrics.averageFileBytes(),
                                metrics.smallFileCount(),
                                metrics.smallFileRatio(),
                                metrics.partitionCount(),
                                metrics.partitionSkew()),
                        new HealthAssessment.DeletionVectors(
                                metrics.filesWithDeletionVectors(),
                                metrics.deletionVectorBytes(),
                                metrics.deletedRowCount(),
                                0),
                        new HealthAssessment.TransactionLog(
                                metrics.version(), 0, Optional.empty(), "UNKNOWN", 0, 0, false, 0),
                        new HealthAssessment.StorageRetention(0, 0, 168),
                        new HealthAssessment.Clustering(
                                metrics.partitionColumns(),
                                metrics.clusteringColumns(),
                                Optional.empty(),
                                0),
                        new HealthAssessment.Protocol(
                                snapshot.minReaderVersion(),
                                snapshot.minWriterVersion(),
                                snapshot.tableFeatures().stream().sorted().toList(),
                                snapshot.accessMode(),
                                snapshot.filesystemVisibleStateCurrent()),
                        issues);
        var view = store.saveHealth(workspaceId, tableId, assessed, debtScore(assessed));
        store.audit(
                workspaceId,
                "TABLE_HEALTH_REFRESHED",
                issues.isEmpty() ? "INFO" : "WARN",
                principal,
                "table",
                tableId.toString(),
                Map.of("observedVersion", metrics.version(), "provenance", assessed.provenance()));
        events.publish(
                workspaceId,
                "TABLE_HEALTH_REFRESHED",
                Map.of("tableId", tableId, "observedVersion", metrics.version()));
        return view;
    }

    private static List<HealthIssue> issues(long smallFiles, double smallFileRatio, long dvFiles) {
        var issues = new ArrayList<HealthIssue>();
        if (smallFiles >= 20 && smallFileRatio >= 0.30) {
            issues.add(
                    new HealthIssue(
                            "DELTA_SMALL_FILES",
                            smallFileRatio >= 0.60
                                    ? HealthSeverity.CRITICAL
                                    : HealthSeverity.WARNING,
                            "FILE_LAYOUT",
                            smallFiles
                                    + " small files represent "
                                    + Math.round(smallFileRatio * 100)
                                    + "% of active files",
                            "Review an OPTIMIZE_BINPACK plan"));
        }
        if (dvFiles > 0) {
            issues.add(
                    new HealthIssue(
                            "DELTA_DELETION_VECTORS",
                            HealthSeverity.WARNING,
                            "DELETION_VECTORS",
                            dvFiles + " active files contain deletion vectors",
                            "Review a REORG_PURGE plan before any retention cleanup"));
        }
        return List.copyOf(issues);
    }

    private static double debtScore(HealthAssessment value) {
        var layout = Math.min(400, value.fileLayout().smallFileRatio() * 400);
        var deletionVectors =
                Math.min(300, value.deletionVectors().filesWithDeletionVectors() * 5.0);
        var clustering = Math.min(200, value.clustering().unclusteredFileRatio() * 200);
        return Math.min(1000, layout + deletionVectors + clustering);
    }
}
