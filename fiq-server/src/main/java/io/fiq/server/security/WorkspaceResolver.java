package io.fiq.server.security;

import io.fiq.server.api.ApiException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@RequestScoped
public class WorkspaceResolver {
    @Context HttpHeaders headers;
    @jakarta.inject.Inject ApiKeyContext apiKey;

    @ConfigProperty(name = "fiq.default-workspace-id")
    String defaultWorkspaceId;

    public UUID workspaceId() {
        var header = headers.getHeaderString("X-FIQ-Workspace");
        if (apiKey.authenticated()) {
            var keyWorkspace = apiKey.authentication().workspaceId();
            if (header == null || header.isBlank()) return keyWorkspace;
            try {
                if (!keyWorkspace.equals(UUID.fromString(header))) {
                    throw new ApiException(
                            Response.Status.FORBIDDEN,
                            "FIQ_API_KEY_WORKSPACE_MISMATCH",
                            "The API key is not valid for the requested workspace");
                }
                return keyWorkspace;
            } catch (IllegalArgumentException exception) {
                throw new ApiException(
                        Response.Status.BAD_REQUEST,
                        "FIQ_INVALID_WORKSPACE",
                        "X-FIQ-Workspace must be a UUID");
            }
        }
        try {
            return UUID.fromString(
                    header == null || header.isBlank() ? defaultWorkspaceId : header);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    Response.Status.BAD_REQUEST,
                    "FIQ_INVALID_WORKSPACE",
                    "X-FIQ-Workspace must be a UUID");
        }
    }
}
