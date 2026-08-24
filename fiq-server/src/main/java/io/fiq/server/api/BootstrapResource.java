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
import java.time.Instant;

@Path("/api/v1/bootstrap")
@Produces(MediaType.APPLICATION_JSON)
public class BootstrapResource {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    public ApiModels.Bootstrap get() {
        access.require(Role.VIEWER);
        var id = workspace.workspaceId();
        var selectedWorkspace = store.workspace(id);
        return new ApiModels.Bootstrap(
                selectedWorkspace,
                store.defaultEnvironment(id),
                access.highestRole(),
                selectedWorkspace.timezone(),
                Instant.now(),
                "0.1.0");
    }
}
