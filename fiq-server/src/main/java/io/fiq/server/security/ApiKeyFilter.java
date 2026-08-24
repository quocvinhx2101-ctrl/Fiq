package io.fiq.server.security;

import io.fiq.server.api.ApiException;
import io.fiq.server.persistence.FiqStore;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter implements ContainerRequestFilter {
    @Inject FiqStore store;
    @Inject ApiKeyContext context;

    @ConfigProperty(name = "fiq.auth.enabled")
    boolean enabled;

    @Override
    public void filter(ContainerRequestContext request) {
        if (!enabled) return;
        var value = request.getHeaderString("X-API-Key");
        if (value == null || value.isBlank()) return;
        if (!value.startsWith("fiq_") || value.length() < 24) {
            throw unauthorized();
        }
        var authentication =
                store.authenticateApiKey(value).orElseThrow(ApiKeyFilter::unauthorized);
        context.authenticate(authentication);
    }

    private static ApiException unauthorized() {
        return new ApiException(
                Response.Status.UNAUTHORIZED,
                "FIQ_API_KEY_INVALID",
                "The API key is invalid, expired, or disabled");
    }
}
