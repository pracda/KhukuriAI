package ai.khukuri.incident.detection;

import ai.khukuri.incident.config.IncidentProperties;
import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.service.IncidentService;
import ai.khukuri.incident.telemetry.TelemetryQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates detection rules against recent telemetry on a fixed schedule.
 *
 * <p>Each rule is symmetric: when the condition holds it opens (or refreshes) an incident,
 * and when it stops holding it auto-resolves. Without the resolve half, a transient blip
 * would leave an incident open forever and the board would fill with noise nobody trusts.
 *
 * <p>Rules are threshold-based on purpose. Anomaly detection is the obvious next step, but
 * a threshold an engineer can read and predict is a better foundation than a model whose
 * false positives are unexplainable.
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    public static final String RULE_ERROR_RATE = "error-rate";
    public static final String RULE_LATENCY = "latency-p95";
    public static final String RULE_SATURATION_PREFIX = "saturation:";

    private final IncidentProperties props;
    private final TelemetryQueryService telemetry;
    private final IncidentService incidents;

    public DetectionService(IncidentProperties props, TelemetryQueryService telemetry,
                            IncidentService incidents) {
        this.props = props;
        this.telemetry = telemetry;
        this.incidents = incidents;
    }

    // ISO-8601 duration: fixedDelayString rejects the "30s" shorthand.
    @Scheduled(fixedDelayString = "${incident.detection.interval:PT30S}")
    public void evaluate() {
        if (!props.detection().enabled()) {
            return;
        }
        try {
            for (String tenant : tenantsToSweep()) {
                evaluateTenant(tenant);
            }
        } catch (Exception e) {
            // A detector that dies on one bad pass stops protecting everything.
            log.error("Detection pass failed: {}", e.getMessage());
        }
    }

    /** Visible for testing — one full pass over a single tenant. */
    public void evaluateTenant(String tenant) {
        int window = (int) props.detection().window().toSeconds();
        evaluateErrorRate(tenant, window);
        evaluateLatency(tenant, window);
        evaluateSaturation(tenant, window);
    }

    private List<String> tenantsToSweep() {
        List<String> configured = props.detection().tenants();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        // Nothing configured: sweep whoever is actually shipping telemetry.
        return telemetry.activeTenants((int) props.detection().window().toSeconds());
    }

    private void evaluateErrorRate(String tenant, int window) {
        var rule = props.detection().errorRate();
        if (rule == null || !rule.enabled()) {
            return;
        }
        for (var health : telemetry.serviceHealth(tenant, window)) {
            boolean firing = health.total() >= rule.minEvents()
                    && health.errorRate() >= rule.threshold();
            if (firing) {
                incidents.openOrRefresh(tenant, health.serviceName(), RULE_ERROR_RATE,
                        "Elevated error rate on %s".formatted(health.serviceName()),
                        "%.1f%% of log records in the last %ds were errors (%d of %d), above the %.1f%% threshold."
                                .formatted(health.errorRate() * 100, window, health.errors(),
                                        health.total(), rule.threshold() * 100),
                        severityFor(health.errorRate(), rule.threshold()),
                        health.errorRate(), rule.threshold());
            } else {
                incidents.autoResolve(tenant, health.serviceName(), RULE_ERROR_RATE);
            }
        }
    }

    private void evaluateLatency(String tenant, int window) {
        var rule = props.detection().latency();
        if (rule == null || !rule.enabled()) {
            return;
        }
        Map<String, TelemetryQueryService.LatencyStat> byService =
                telemetry.latencyByService(tenant, window);
        for (var entry : byService.entrySet()) {
            var stat = entry.getValue();
            boolean firing = stat.count() >= rule.minSpans() && stat.p95Ms() >= rule.p95ThresholdMs();
            if (firing) {
                incidents.openOrRefresh(tenant, entry.getKey(), RULE_LATENCY,
                        "Slow responses from %s".formatted(entry.getKey()),
                        "p95 latency is %.0fms over the last %ds across %d spans, above the %dms threshold."
                                .formatted(stat.p95Ms(), window, stat.count(), rule.p95ThresholdMs()),
                        severityFor(stat.p95Ms(), rule.p95ThresholdMs()),
                        stat.p95Ms(), rule.p95ThresholdMs());
            } else {
                incidents.autoResolve(tenant, entry.getKey(), RULE_LATENCY);
            }
        }
    }

    private void evaluateSaturation(String tenant, int window) {
        List<IncidentProperties.Saturation> rules = props.detection().saturation();
        if (rules == null) {
            return;
        }
        for (var rule : rules) {
            String ruleKey = RULE_SATURATION_PREFIX + rule.metric();
            Map<String, Double> latest =
                    telemetry.latestMetricByService(tenant, rule.metric(), window);
            for (var entry : latest.entrySet()) {
                if (entry.getValue() >= rule.threshold()) {
                    incidents.openOrRefresh(tenant, entry.getKey(), ruleKey,
                            "%s saturated on %s".formatted(rule.metric(), entry.getKey()),
                            "%s is at %.2f, at or above the %.2f threshold. %s".formatted(
                                    rule.metric(), entry.getValue(), rule.threshold(),
                                    rule.description() == null ? "" : rule.description()).trim(),
                            severityFor(entry.getValue(), rule.threshold()),
                            entry.getValue(), rule.threshold());
                } else {
                    incidents.autoResolve(tenant, entry.getKey(), ruleKey);
                }
            }
        }
    }

    /** How far past the threshold decides how loud the incident is. */
    static Incident.Severity severityFor(double observed, double threshold) {
        if (threshold <= 0) {
            return Incident.Severity.MEDIUM;
        }
        double ratio = observed / threshold;
        if (ratio >= 3) {
            return Incident.Severity.CRITICAL;
        }
        if (ratio >= 2) {
            return Incident.Severity.HIGH;
        }
        if (ratio >= 1.25) {
            return Incident.Severity.MEDIUM;
        }
        return Incident.Severity.LOW;
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
