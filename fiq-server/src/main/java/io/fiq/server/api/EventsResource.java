package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.events.FiqEventBus;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/events")
public class EventsResource {
    @Inject FiqEventBus events;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<FiqEventBus.Event> stream() {
        access.require(Role.VIEWER);
        return events.stream(workspace.workspaceId());
    }
}
