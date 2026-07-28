package ai.khukuri.incident.web;

import ai.khukuri.incident.security.TenantAccess;
import ai.khukuri.incident.telemetry.TelemetryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Telemetry Query API — the agent's tool surface, and the reason this service exists
 * separately from Ingest.
 *
 * <p>Endpoints map one-to-one onto investigation questions ("what is erroring?", "is this
 * metric climbing?") rather than exposing SQL, so an agent's tool schema stays typed and
 * a compromised or confused model cannot express an arbitrary query.
 *
 * <p>Every method resolves the tenant through {@link TenantAccess} before touching data.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryQueryController {

    private static final int MAX_WINDOW_SECONDS = 7 * 24 * 3600;
    private static final int MAX_LIMIT = 200;

    private final TelemetryQueryService telemetry;
    private final TenantAccess tenantAccess;

    public TelemetryQueryController(TelemetryQueryService telemetry, TenantAccess tenantAccess) {
        this.telemetry = telemetry;
        this.tenantAccess = tenantAccess;
    }

    @GetMapping("/error-logs")
    public List<Map<String, Object>> errorLogs(
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "1800") int windowSeconds,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : tenants(tenant)) {
            for (var group : telemetry.errorLogs(t, window(windowSeconds), service, limit(limit))) {
                out.add(Map.of(
                        "tenant", t,
                        "service", group.serviceName(),
                        "sample", group.sample(),
                        "count", group.count()));
            }
        }
        return out;
    }

    @GetMapping("/metric")
    public List<Map<String, Object>> metric(
            @RequestParam String name,
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "3600") int windowSeconds,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "60") int bucketSeconds) {

        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : tenants(tenant)) {
            for (var point : telemetry.metric(t, name, window(windowSeconds), service,
                    Math.max(1, bucketSeconds))) {
                out.add(Map.of(
                        "tenant", t,
                        "timestamp", point.timestamp().toString(),
                        "service", point.serviceName(),
                        "value", point.value()));
            }
        }
        return out;
    }

    @GetMapping("/service-health")
    public List<Map<String, Object>> serviceHealth(
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "1800") int windowSeconds) {

        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : tenants(tenant)) {
            for (var health : telemetry.serviceHealth(t, window(windowSeconds))) {
                out.add(Map.of(
                        "tenant", t,
                        "service", health.serviceName(),
                        "total", health.total(),
                        "errors", health.errors(),
                        "errorRate", health.errorRate()));
            }
        }
        return out;
    }

    @GetMapping("/slow-spans")
    public List<Map<String, Object>> slowSpans(
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "1800") int windowSeconds,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : tenants(tenant)) {
            for (var span : telemetry.slowSpans(t, window(windowSeconds), limit(limit))) {
                out.add(Map.of(
                        "tenant", t,
                        "service", span.serviceName(),
                        "span", span.name(),
                        "p95Ms", span.p95Ms(),
                        "count", span.count()));
            }
        }
        return out;
    }

    /**
     * Resolves the requested tenant to the concrete list to query. Unrestricted callers
     * asking for "everything" are expanded to tenants with recent telemetry rather than
     * being handed a wildcard, so the query layer never sees an unfiltered read.
     */
    private List<String> tenants(String requested) {
        List<String> resolved = tenantAccess.resolve(requested);
        if (resolved.size() == 1 && TenantAccess.ALL.equals(resolved.get(0))) {
            return telemetry.activeTenants(MAX_WINDOW_SECONDS);
        }
        return resolved;
    }

    private static int window(int requested) {
        return Math.max(1, Math.min(requested, MAX_WINDOW_SECONDS));
    }

    private static int limit(int requested) {
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }
}
