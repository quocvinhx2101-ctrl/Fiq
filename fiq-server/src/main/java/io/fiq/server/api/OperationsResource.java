package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import io.fiq.server.service.OperationService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/v1/operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationsResource {
    @Inject FiqStore store;
    @Inject OperationService service;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    public ApiModels.Page<ApiModels.OperationView> list(@QueryParam("limit") Integer limit) {
        access.require(Role.VIEWER);
        return store.listOperations(workspace.workspaceId(), limit == null ? 100 : limit);
    }

    @GET
    @Path("/{operationId}")
    public ApiModels.OperationView get(@PathParam("operationId") UUID operationId) {
        access.require(Role.VIEWER);
        return store.operation(workspace.workspaceId(), operationId);
    }

    @POST
    @Path("/plan")
    public ApiModels.OperationView plan(@Valid ApiModels.PlanRequest request) {
        return service.plan(workspace.workspaceId(), request);
    }

    @POST
    @Path("/{operationId}/execute")
    public ApiModels.OperationView execute(@PathParam("operationId") UUID operationId) {
        return service.queue(workspace.workspaceId(), operationId);
    }

    @POST
    @Path("/{operationId}/approval")
    public ApiModels.OperationView approve(
            @PathParam("operationId") UUID operationId, @Valid ApiModels.ApprovalRequest request) {
        return service.approve(workspace.workspaceId(), operationId, request);
    }

    @POST
    @Path("/{operationId}/cancel")
    public ApiModels.OperationView cancel(@PathParam("operationId") UUID operationId) {
        return service.cancel(workspace.workspaceId(), operationId);
    }

    @POST
    @Path("/{operationId}/retry")
    public ApiModels.OperationView retry(@PathParam("operationId") UUID operationId) {
        return service.retry(workspace.workspaceId(), operationId);
    }
}
