package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/overview")
@Produces(MediaType.APPLICATION_JSON)
public class OverviewResource {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    public ApiModels.Overview get() {
        access.require(Role.VIEWER);
        return store.overview(workspace.workspaceId());
    }
}
