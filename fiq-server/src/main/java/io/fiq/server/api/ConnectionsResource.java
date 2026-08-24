package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import io.fiq.server.service.ConnectionService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/connections")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectionsResource {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;
    @Inject ConnectionService service;

    @GET
    public List<ApiModels.ConnectionView> list() {
        access.require(Role.VIEWER);
        return store.listConnections(workspace.workspaceId());
    }

    @POST
    public ApiModels.ConnectionView create(@Valid ApiModels.ConnectionRequest request) {
        access.require(Role.ADMIN);
        return store.createConnection(workspace.workspaceId(), request);
    }

    @GET
    @Path("/{connectionId}")
    public ApiModels.ConnectionView get(@PathParam("connectionId") UUID connectionId) {
        access.require(Role.VIEWER);
        return store.connection(workspace.workspaceId(), connectionId);
    }

    @POST
    @Path("/{connectionId}/test")
    public ApiModels.ConnectionTestResult test(@PathParam("connectionId") UUID connectionId) {
        return service.test(workspace.workspaceId(), connectionId);
    }

    @POST
    @Path("/{connectionId}/discover")
    public ApiModels.DiscoveryRunView discover(
            @PathParam("connectionId") UUID connectionId,
            @Valid ApiModels.DiscoveryRequest request) {
        return discovery.discover(workspace.workspaceId(), connectionId, request);
    }

    @GET
    @Path("/discovery-runs/{runId}")
    public ApiModels.DiscoveryRunView discoveryRun(@PathParam("runId") UUID runId) {
        access.require(Role.VIEWER);
        return store.discoveryRun(workspace.workspaceId(), runId);
    }

    @Inject io.fiq.server.service.DiscoveryService discovery;
}
