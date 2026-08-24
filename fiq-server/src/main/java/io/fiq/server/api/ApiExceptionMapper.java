package io.fiq.server.api;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;
import java.time.Instant;

@Provider
public final class ApiExceptionMapper implements ExceptionMapper<ApiException> {
    @Context UriInfo uriInfo;

    @Override
    public Response toResponse(ApiException exception) {
        var problem =
                new ApiProblem(
                        URI.create("https://fiq.dev/problems/" + exception.code().toLowerCase()),
                        exception.status().getReasonPhrase(),
                        exception.status().getStatusCode(),
                        exception.getMessage(),
                        uriInfo.getRequestUri().getPath(),
                        Instant.now(),
                        exception.code());
        return Response.status(exception.status())
                .type("application/problem+json")
                .entity(problem)
                .build();
    }
}
