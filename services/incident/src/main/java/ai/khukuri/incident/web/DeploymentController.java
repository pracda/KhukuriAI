package ai.khukuri.incident.web;

import ai.khukuri.incident.domain.Deployment;
import ai.khukuri.incident.repository.DeploymentRepository;
import ai.khukuri.incident.security.TenantAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deployment records — the "what changed?" half of root-cause analysis, and the second
 * tool the ops-analyst reaches for after seeing errors.
 */
@RestController
@RequestMapping("/api/v1/deployments")
public class DeploymentController {

    private final DeploymentRepository deployments;
    private final TenantAccess tenantAccess;

    public DeploymentController(DeploymentRepository deployments, TenantAccess tenantAccess) {
        this.deployments = deployments;
        this.tenantAccess = tenantAccess;
    }

    public record RecordDeploymentRequest(
            @NotBlank String tenant,
            @NotBlank String service,
            @NotBlank String version,
            Instant deployedAt,
            String notes) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> record(@Valid @RequestBody RecordDeploymentRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        tenantAccess.resolve(request.tenant());
        Deployment deployment = new Deployment(
                request.tenant(), request.service(), request.version(),
                request.deployedAt(), jwt != null ? jwt.getSubject() : "unknown", request.notes());
        return toDto(deployments.save(deployment));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String tenant,
                                          @RequestParam(defaultValue = "86400") int windowSeconds) {
        List<String> scope = tenantAccess.resolve(tenant);
        Instant since = Instant.now().minusSeconds(Math.max(1, windowSeconds));

        List<Deployment> found;
        if (scope.size() == 1 && TenantAccess.ALL.equals(scope.get(0))) {
            found = deployments.findAll().stream()
                    .filter(d -> d.getDeployedAt().isAfter(since))
                    .sorted((a, b) -> b.getDeployedAt().compareTo(a.getDeployedAt()))
                    .toList();
        } else {
            found = deployments.findByTenantIdInAndDeployedAtAfterOrderByDeployedAtDesc(scope, since);
        }
        return found.stream().map(DeploymentController::toDto).toList();
    }

    private static Map<String, Object> toDto(Deployment d) {
        return Map.of(
                "id", d.getId().toString(),
                "tenant", d.getTenantId(),
                "service", d.getServiceName(),
                "version", d.getVersion(),
                "deployedAt", d.getDeployedAt().toString(),
                "deployedBy", d.getDeployedBy() == null ? "" : d.getDeployedBy(),
                "notes", d.getNotes() == null ? "" : d.getNotes());
    }
}
