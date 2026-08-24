package io.fiq.server.api;

import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/api-keys")
@Produces(MediaType.APPLICATION_JSON)
public class ApiKeysResource {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    public List<ApiModels.ApiKeyView> list() {
        access.require(Role.ADMIN);
        return store.listApiKeys(workspace.workspaceId());
    }

    @POST
    public ApiModels.CreatedApiKey create(@Valid ApiModels.ApiKeyRequest request) {
        access.require(Role.ADMIN);
        if (request.roles().isEmpty()) {
            throw new ApiException(
                    422, "FIQ_API_KEY_ROLES_REQUIRED", "At least one role is required");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(
                    422, "FIQ_API_KEY_EXPIRY_INVALID", "Expiry must be in the future");
        }
        var random = new byte[32];
        RANDOM.nextBytes(random);
        var secret = "fiq_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var key = store.createApiKey(workspace.workspaceId(), request, secret);
        store.audit(
                workspace.workspaceId(),
                "API_KEY_CREATED",
                "INFO",
                access.principal(),
                "api_key",
                key.id().toString(),
                Map.of("roles", request.roles()));
        return new ApiModels.CreatedApiKey(key, secret);
    }

    @DELETE
    @Path("/{keyId}")
    public Response revoke(@PathParam("keyId") UUID keyId) {
        access.require(Role.ADMIN);
        store.revokeApiKey(workspace.workspaceId(), keyId);
        store.audit(
                workspace.workspaceId(),
                "API_KEY_REVOKED",
                "INFO",
                access.principal(),
                "api_key",
                keyId.toString(),
                Map.of());
        return Response.noContent().build();
    }
}
