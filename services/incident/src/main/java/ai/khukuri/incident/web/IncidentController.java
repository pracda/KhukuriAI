package ai.khukuri.incident.web;

import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.domain.IncidentStatus;
import ai.khukuri.incident.repository.IncidentRepository;
import ai.khukuri.incident.security.TenantAccess;
import ai.khukuri.incident.service.IncidentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final TenantAccess tenantAccess;

    public IncidentController(IncidentRepository incidents, IncidentService incidentService,
                              TenantAccess tenantAccess) {
        this.incidents = incidents;
        this.incidentService = incidentService;
        this.tenantAccess = tenantAccess;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String tenant,
                                          @RequestParam(required = false) IncidentStatus status) {
        List<String> scope = tenantAccess.resolve(tenant);
        boolean unrestricted = scope.size() == 1 && TenantAccess.ALL.equals(scope.get(0));

        List<Incident> found;
        if (unrestricted) {
            found = status == null
                    ? incidents.findAll(org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "openedAt"))
                    : incidents.findAll(org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "openedAt"))
                            .stream().filter(i -> i.getStatus() == status).toList();
        } else {
            found = status == null
                    ? incidents.findByTenantIdInOrderByOpenedAtDesc(scope)
                    : incidents.findByTenantIdInAndStatusOrderByOpenedAtDesc(scope, status);
        }
        return found.stream().map(IncidentController::toDto).toList();
    }

    @GetMapping("/{reference}")
    public Map<String, Object> get(@PathVariable String reference) {
        Incident incident = incidents.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such incident: " + reference));
        // Reading one incident must respect the same boundary as listing them.
        tenantAccess.resolve(incident.getTenantId());
        return toDto(incident);
    }

    @PostMapping("/{reference}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable String reference,
                                           @AuthenticationPrincipal Jwt jwt) {
        assertAccess(reference);
        return toDto(incidentService.acknowledge(reference,
                jwt != null ? jwt.getSubject() : "unknown"));
    }

    @PostMapping("/{reference}/mitigate")
    public Map<String, Object> mitigate(@PathVariable String reference) {
        assertAccess(reference);
        return toDto(incidentService.mitigate(reference));
    }

    @PostMapping("/{reference}/resolve")
    public Map<String, Object> resolve(@PathVariable String reference) {
        assertAccess(reference);
        return toDto(incidentService.resolve(reference));
    }

    private void assertAccess(String reference) {
        Incident incident = incidents.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such incident: " + reference));
        tenantAccess.resolve(incident.getTenantId());
    }

    static Map<String, Object> toDto(Incident i) {
        return Map.ofEntries(
                Map.entry("reference", i.getReference()),
                Map.entry("tenant", i.getTenantId()),
                Map.entry("service", i.getServiceName()),
                Map.entry("rule", i.getRuleKey()),
                Map.entry("title", i.getTitle()),
                Map.entry("summary", i.getSummary()),
                Map.entry("status", i.getStatus().name()),
                Map.entry("severity", i.getSeverity().name()),
                Map.entry("observedValue", i.getObservedValue()),
                Map.entry("thresholdValue", i.getThresholdValue()),
                Map.entry("openedAt", i.getOpenedAt().toString()),
                Map.entry("updatedAt", i.getUpdatedAt().toString()),
                Map.entry("resolvedAt", i.getResolvedAt() == null ? "" : i.getResolvedAt().toString()),
                Map.entry("acknowledgedBy", i.getAcknowledgedBy() == null ? "" : i.getAcknowledgedBy()));
    }

    static Instant now() {
        return Instant.now();
    }
}
