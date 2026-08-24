package io.fiq.server.security;

import io.fiq.domain.Role;
import io.fiq.server.api.ApiException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AccessControl {
    @Inject SecurityIdentity identity;
    @Inject ApiKeyContext apiKey;

    @ConfigProperty(name = "fiq.auth.enabled")
    boolean enabled;

    public String principal() {
        if (apiKey.authenticated()) return apiKey.authentication().principal();
        if (!enabled || identity == null || identity.isAnonymous()) return "development-user";
        return identity.getPrincipal().getName();
    }

    public String highestRole() {
        if (!enabled) return Role.ADMIN.name();
        if (apiKey.authenticated()) {
            for (var role : new Role[] {Role.ADMIN, Role.APPROVER, Role.OPERATOR, Role.VIEWER}) {
                if (apiKey.authentication().roles().contains(role)) return role.name();
            }
            return "NONE";
        }
        for (var role : new Role[] {Role.ADMIN, Role.APPROVER, Role.OPERATOR, Role.VIEWER}) {
            if (identity.hasRole(role.name())) return role.name();
        }
        return "NONE";
    }

    public void require(Role required) {
        if (!enabled) return;
        var allowed =
                switch (required) {
                    case VIEWER -> hasAny(Role.VIEWER, Role.OPERATOR, Role.APPROVER, Role.ADMIN);
                    case OPERATOR -> hasAny(Role.OPERATOR, Role.ADMIN);
                    case APPROVER -> hasAny(Role.APPROVER, Role.ADMIN);
                    case ADMIN -> hasAny(Role.ADMIN);
                };
        if (!allowed) {
            throw new ApiException(
                    Response.Status.FORBIDDEN,
                    "FIQ_PERMISSION_DENIED",
                    "The current principal does not have the required " + required + " role");
        }
    }

    private boolean hasAny(Role... roles) {
        if (apiKey.authenticated()) {
            for (var role : roles) {
                if (apiKey.authentication().roles().contains(role)) return true;
            }
            return false;
        }
        for (var role : roles) if (identity.hasRole(role.name())) return true;
        return false;
    }
}
