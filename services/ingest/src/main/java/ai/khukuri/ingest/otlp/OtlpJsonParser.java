package ai.khukuri.ingest.otlp;

import ai.khukuri.ingest.model.Signal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens OTLP/JSON batches into ClickHouse rows.
 *
 * <p>OTLP nests as resource → scope → records, with resource-level attributes (notably
 * {@code service.name}) applying to everything beneath. This walks that tree and denormalizes,
 * copying the resource attributes onto each row so queries never need a join.
 *
 * <p>Field names follow the proto3 JSON mapping (camelCase) which is what the OpenTelemetry
 * Collector emits; the snake_case proto field names are accepted as aliases since some SDKs
 * emit them.
 */
@Component
public class OtlpJsonParser {

    private final ObjectMapper mapper;

    public OtlpJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TelemetryRows.Batch parse(String json, Signal signal, String tenantId) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Body is not valid JSON: " + e.getMessage());
        }
        return switch (signal) {
            case LOGS -> new TelemetryRows.Batch(parseLogs(root, tenantId), List.of(), List.of());
            case METRICS -> new TelemetryRows.Batch(List.of(), parseMetrics(root, tenantId), List.of());
            case TRACES -> new TelemetryRows.Batch(List.of(), List.of(), parseSpans(root, tenantId));
        };
    }

    // ── logs ────────────────────────────────────────────────────────────────

    private List<TelemetryRows.LogRow> parseLogs(JsonNode root, String tenantId) {
        List<TelemetryRows.LogRow> rows = new ArrayList<>();
        for (JsonNode resourceLogs : array(root, "resourceLogs", "resource_logs")) {
            Map<String, String> resourceAttrs = attributes(resourceLogs.path("resource"));
            String service = resourceAttrs.getOrDefault("service.name", "unknown");
            for (JsonNode scopeLogs : array(resourceLogs, "scopeLogs", "scope_logs")) {
                for (JsonNode record : array(scopeLogs, "logRecords", "log_records")) {
                    Map<String, String> attrs = new LinkedHashMap<>(resourceAttrs);
                    attrs.putAll(attributes(record));
                    rows.add(new TelemetryRows.LogRow(
                            timestamp(record, "timeUnixNano", "time_unix_nano",
                                    "observedTimeUnixNano", "observed_time_unix_nano"),
                            tenantId,
                            service,
                            text(record, "severityText", "severity_text"),
                            (int) longValue(record, 0, "severityNumber", "severity_number"),
                            anyValue(record.path("body")),
                            text(record, "traceId", "trace_id"),
                            text(record, "spanId", "span_id"),
                            attrs));
                }
            }
        }
        return rows;
    }

    // ── metrics ─────────────────────────────────────────────────────────────

    private List<TelemetryRows.MetricRow> parseMetrics(JsonNode root, String tenantId) {
        List<TelemetryRows.MetricRow> rows = new ArrayList<>();
        for (JsonNode resourceMetrics : array(root, "resourceMetrics", "resource_metrics")) {
            Map<String, String> resourceAttrs = attributes(resourceMetrics.path("resource"));
            String service = resourceAttrs.getOrDefault("service.name", "unknown");
            for (JsonNode scopeMetrics : array(resourceMetrics, "scopeMetrics", "scope_metrics")) {
                for (JsonNode metric : array(scopeMetrics, "metrics")) {
                    String name = text(metric, "name");
                    // gauge/sum carry comparable numeric points; histograms are summarized
                    // by their running sum, which is what "is this climbing?" questions need.
                    collectPoints(metric.path("gauge"), name, service, tenantId, resourceAttrs, rows);
                    collectPoints(metric.path("sum"), name, service, tenantId, resourceAttrs, rows);
                    collectPoints(metric.path("histogram"), name, service, tenantId, resourceAttrs, rows);
                }
            }
        }
        return rows;
    }

    private void collectPoints(JsonNode container, String metricName, String service, String tenantId,
                               Map<String, String> resourceAttrs, List<TelemetryRows.MetricRow> out) {
        if (container.isMissingNode() || container.isNull()) {
            return;
        }
        for (JsonNode point : array(container, "dataPoints", "data_points")) {
            Double value = pointValue(point);
            if (value == null) {
                continue;
            }
            Map<String, String> attrs = new LinkedHashMap<>(resourceAttrs);
            attrs.putAll(attributes(point));
            out.add(new TelemetryRows.MetricRow(
                    timestamp(point, "timeUnixNano", "time_unix_nano",
                            "startTimeUnixNano", "start_time_unix_nano"),
                    tenantId, service, metricName, value, attrs));
        }
    }

    private Double pointValue(JsonNode point) {
        for (String field : new String[]{"asDouble", "as_double"}) {
            if (point.hasNonNull(field)) {
                return point.get(field).asDouble();
            }
        }
        for (String field : new String[]{"asInt", "as_int"}) {
            if (point.hasNonNull(field)) {
                // proto3 JSON encodes 64-bit ints as strings
                return Double.parseDouble(point.get(field).asText("0"));
            }
        }
        if (point.hasNonNull("sum")) {
            return point.get("sum").asDouble();
        }
        return null;
    }

    // ── traces ──────────────────────────────────────────────────────────────

    private List<TelemetryRows.SpanRow> parseSpans(JsonNode root, String tenantId) {
        List<TelemetryRows.SpanRow> rows = new ArrayList<>();
        for (JsonNode resourceSpans : array(root, "resourceSpans", "resource_spans")) {
            Map<String, String> resourceAttrs = attributes(resourceSpans.path("resource"));
            String service = resourceAttrs.getOrDefault("service.name", "unknown");
            for (JsonNode scopeSpans : array(resourceSpans, "scopeSpans", "scope_spans")) {
                for (JsonNode span : array(scopeSpans, "spans")) {
                    long start = longValue(span, 0, "startTimeUnixNano", "start_time_unix_nano");
                    long end = longValue(span, 0, "endTimeUnixNano", "end_time_unix_nano");
                    Map<String, String> attrs = new LinkedHashMap<>(resourceAttrs);
                    attrs.putAll(attributes(span));
                    rows.add(new TelemetryRows.SpanRow(
                            start > 0 ? nanosToInstant(start) : Instant.now(),
                            tenantId, service,
                            text(span, "traceId", "trace_id"),
                            text(span, "spanId", "span_id"),
                            text(span, "parentSpanId", "parent_span_id"),
                            text(span, "name"),
                            end > start ? end - start : 0L,
                            (int) longValue(span.path("status"), 0, "code"),
                            attrs));
                }
            }
        }
        return rows;
    }

    // ── shared helpers ──────────────────────────────────────────────────────

    private static List<JsonNode> array(JsonNode parent, String... names) {
        for (String name : names) {
            JsonNode node = parent.path(name);
            if (node.isArray()) {
                List<JsonNode> items = new ArrayList<>(node.size());
                node.forEach(items::add);
                return items;
            }
        }
        return List.of();
    }

    /** OTLP attributes are a list of {key, value:{typedField}} pairs. */
    private static Map<String, String> attributes(JsonNode parent) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (JsonNode attribute : array(parent, "attributes")) {
            String key = attribute.path("key").asText(null);
            if (key != null) {
                attrs.put(key, anyValue(attribute.path("value")));
            }
        }
        return attrs;
    }

    /** Renders an OTLP AnyValue as a string — the common denominator for a Map(String,String). */
    private static String anyValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        for (String field : new String[]{"stringValue", "string_value"}) {
            if (value.hasNonNull(field)) {
                return value.get(field).asText();
            }
        }
        for (String field : new String[]{"intValue", "int_value", "doubleValue", "double_value",
                "boolValue", "bool_value"}) {
            if (value.hasNonNull(field)) {
                return value.get(field).asText();
            }
        }
        for (String field : new String[]{"arrayValue", "array_value", "kvlistValue", "kvlist_value"}) {
            if (value.hasNonNull(field)) {
                return value.get(field).toString();
            }
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String text(JsonNode parent, String... names) {
        for (String name : names) {
            if (parent.hasNonNull(name)) {
                return parent.get(name).asText();
            }
        }
        return "";
    }

    private static long longValue(JsonNode parent, long fallback, String... names) {
        for (String name : names) {
            if (parent.hasNonNull(name)) {
                JsonNode node = parent.get(name);
                // proto3 JSON encodes 64-bit ints as strings; tolerate both forms
                return node.isTextual() ? parseLong(node.asText(), fallback) : node.asLong(fallback);
            }
        }
        return fallback;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Instant timestamp(JsonNode parent, String... names) {
        long nanos = longValue(parent, 0, names);
        return nanos > 0 ? nanosToInstant(nanos) : Instant.now();
    }

    private static Instant nanosToInstant(long nanos) {
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }

    /** Exposed for the writer, which needs a plain map for ClickHouse's Map(String,String). */
    public static Map<String, String> copyAttributes(Map<String, String> attrs) {
        return new HashMap<>(attrs);
    }
}
