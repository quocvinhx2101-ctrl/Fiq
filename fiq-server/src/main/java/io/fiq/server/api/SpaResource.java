package io.fiq.server.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;

/**
 * Browser-history fallback for client routes; API, management, and static assets never enter it.
 */
@Path("/{route: (?!api(?:/|$)|q(?:/|$)|assets(?:/|$)).+}")
public class SpaResource {
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        try (var input = getClass().getResourceAsStream("/META-INF/resources/index.html")) {
            if (input == null) return Response.status(Response.Status.NOT_FOUND).build();
            return Response.ok(input.readAllBytes(), MediaType.TEXT_HTML_TYPE).build();
        } catch (IOException exception) {
            return Response.serverError().build();
        }
    }
}
