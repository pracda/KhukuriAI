package ai.khukuri.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "incident")
public record IncidentProperties(Detection detection, Kafka kafka) {

    public record Detection(
            boolean enabled,
            Duration window,
            List<String> tenants,
            ErrorRate errorRate,
            Latency latency,
            List<Saturation> saturation) {
    }

    /** Fires when the share of ERROR logs crosses the threshold. */
    public record ErrorRate(boolean enabled, double threshold, int minEvents) {
    }

    /** Fires when p95 span duration crosses the threshold. */
    public record Latency(boolean enabled, long p95ThresholdMs, int minSpans) {
    }

    /** Fires when a named metric's latest value crosses the threshold. */
    public record Saturation(String metric, double threshold, String description) {
    }

    public record Kafka(String topic) {
    }
}
