package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import io.fiq.server.service.HealthAssessmentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/v1/tables")
@Produces(MediaType.APPLICATION_JSON)
public class TablesResource {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;
    @Inject HealthAssessmentService healthAssessmentService;

    @GET
    public ApiModels.Page<ApiModels.TableSummary> list(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") Integer limit,
            @QueryParam("search") String search) {
        access.require(Role.VIEWER);
        return store.listTables(
                workspace.workspaceId(), cursor, limit == null ? 50 : limit, search);
    }

    @GET
    @Path("/{tableId}")
    public ApiModels.TableDetail get(@PathParam("tableId") UUID tableId) {
        access.require(Role.VIEWER);
        return store.tableDetail(workspace.workspaceId(), tableId);
    }

    @GET
    @Path("/{tableId}/health")
    public ApiModels.HealthView health(@PathParam("tableId") UUID tableId) {
        access.require(Role.VIEWER);
        return store.latestHealthView(workspace.workspaceId(), tableId);
    }

    @POST
    @Path("/{tableId}/health/refresh")
    public ApiModels.HealthView refreshHealth(@PathParam("tableId") UUID tableId) {
        return healthAssessmentService.refresh(workspace.workspaceId(), tableId);
    }
}
