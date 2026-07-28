package ai.khukuri.ingest.otlp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Flattened, ClickHouse-shaped rows produced from an OTLP batch. */
public final class TelemetryRows {

    private TelemetryRows() {
    }

    public record LogRow(
            Instant timestamp,
            String tenantId,
            String serviceName,
            String severity,
            int severityNumber,
            String body,
            String traceId,
            String spanId,
            Map<String, String> attributes) {
    }

    public record MetricRow(
            Instant timestamp,
            String tenantId,
            String serviceName,
            String metricName,
            double value,
            Map<String, String> attributes) {
    }

    public record SpanRow(
            Instant timestamp,
            String tenantId,
            String serviceName,
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            long durationNs,
            int statusCode,
            Map<String, String> attributes) {
    }

    public record Batch(List<LogRow> logs, List<MetricRow> metrics, List<SpanRow> spans) {

        public static Batch empty() {
            return new Batch(List.of(), List.of(), List.of());
        }

        public int size() {
            return logs.size() + metrics.size() + spans.size();
        }
    }
}
