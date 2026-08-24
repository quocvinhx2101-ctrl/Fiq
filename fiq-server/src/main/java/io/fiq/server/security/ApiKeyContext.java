package io.fiq.server.security;

import io.fiq.domain.Role;
import jakarta.enterprise.context.RequestScoped;
import java.util.Set;
import java.util.UUID;

@RequestScoped
public class ApiKeyContext {
    private Authentication authentication;

    public boolean authenticated() {
        return authentication != null;
    }

    public Authentication authentication() {
        return authentication;
    }

    void authenticate(Authentication value) {
        authentication = value;
    }

    public record Authentication(UUID keyId, UUID workspaceId, String principal, Set<Role> roles) {
        public Authentication {
            roles = Set.copyOf(roles);
        }
    }
}
