package io.fiq.server.api;

import io.fiq.domain.MaintenancePolicy;
import io.fiq.domain.OperationType;
import io.fiq.domain.Role;
import io.fiq.server.persistence.FiqStore;
import io.fiq.server.security.AccessControl;
import io.fiq.server.security.WorkspaceResolver;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
public class PoliciesResource {
    @Inject FiqStore store;
    @Inject AccessControl access;
    @Inject WorkspaceResolver workspace;

    @GET
    public ApiModels.Page<ApiModels.PolicySummary> list(@QueryParam("limit") Integer limit) {
        access.require(Role.VIEWER);
        return store.listPolicies(workspace.workspaceId(), limit == null ? 100 : limit);
    }

    @POST
    public ApiModels.PolicySummary create(@Valid ApiModels.PolicyRequest request) {
        access.require(Role.ADMIN);
        requireValid(request);
        var saved = store.savePolicy(workspace.workspaceId(), null, request);
        audit("POLICY_CREATED", saved.id());
        return saved;
    }

    @PUT
    @Path("/{policyId}")
    public ApiModels.PolicySummary update(
            @PathParam("policyId") UUID policyId, @Valid ApiModels.PolicyRequest request) {
        access.require(Role.ADMIN);
        requireValid(request);
        var saved = store.savePolicy(workspace.workspaceId(), policyId, request);
        audit("POLICY_UPDATED", saved.id());
        return saved;
    }

    @DELETE
    @Path("/{policyId}")
    public Response delete(@PathParam("policyId") UUID policyId) {
        access.require(Role.ADMIN);
        store.deletePolicy(workspace.workspaceId(), policyId);
        audit("POLICY_DELETED", policyId);
        return Response.noContent().build();
    }

    @POST
    @Path("/validate")
    public ApiModels.PolicyValidation validate(@Valid ApiModels.PolicyRequest request) {
        access.require(Role.OPERATOR);
        return validation(request);
    }

    @POST
    @Path("/{policyId}/simulation")
    public ApiModels.PolicySimulation simulate(@PathParam("policyId") UUID policyId) {
        access.require(Role.VIEWER);
        var policy = store.loadPolicy(workspace.workspaceId(), policyId);
        var matches = store.simulatePolicy(workspace.workspaceId(), policy);
        return new ApiModels.PolicySimulation(matches.size(), matches);
    }

    private ApiModels.PolicyValidation validation(ApiModels.PolicyRequest request) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        try {
            ZoneId.of(request.timezone());
        } catch (RuntimeException exception) {
            errors.add("Timezone is not a valid IANA zone");
        }
        var fields = request.cron().trim().split("\\s+").length;
        if (fields < 6 || fields > 7) errors.add("Cron must contain six or seven Quartz fields");
        if (request.operations().isEmpty()) errors.add("At least one operation must be enabled");
        if (request.operations().contains(OperationType.OPTIMIZE_ZORDER)
                && request.operationConfigs()
                        .getOrDefault(
                                OperationType.OPTIMIZE_ZORDER,
                                new MaintenancePolicy.OperationConfig(
                                        168, java.util.List.of(), "", "", false, false))
                        .zOrderColumns()
                        .isEmpty()) {
            errors.add("OPTIMIZE_ZORDER requires explicit columns");
        }
        for (var operation : request.operations()) {
            var config = request.operationConfigs().get(operation);
            if (operation.isVacuum() && config != null && config.retentionHours() < 168) {
                warnings.add("Retention below 168 hours always requires approval");
            }
        }
        return new ApiModels.PolicyValidation(errors.isEmpty(), errors, warnings);
    }

    private void requireValid(ApiModels.PolicyRequest request) {
        var result = validation(request);
        if (!result.valid()) {
            throw new ApiException(422, "FIQ_POLICY_INVALID", String.join("; ", result.errors()));
        }
    }

    private void audit(String event, UUID id) {
        store.audit(
                workspace.workspaceId(),
                event,
                "INFO",
                access.principal(),
                "policy",
                id.toString(),
                Map.of());
    }
}
