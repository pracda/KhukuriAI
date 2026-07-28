package ai.khukuri.ingest.otlp;

import ai.khukuri.ingest.model.Signal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpJsonParserTest {

    private final OtlpJsonParser parser = new OtlpJsonParser(new ObjectMapper());

    @Test
    void flattensLogsAndInheritsResourceAttributes() {
        String otlp = """
                {"resourceLogs":[{
                  "resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"retail-shop"}},
                    {"key":"deployment.environment","value":{"stringValue":"prod"}}]},
                  "scopeLogs":[{"logRecords":[{
                    "timeUnixNano":"1753600000000000000",
                    "severityNumber":17,
                    "severityText":"ERROR",
                    "body":{"stringValue":"HikariPool-1 connection is not available"},
                    "traceId":"abc123","spanId":"def456",
                    "attributes":[{"key":"http.route","value":{"stringValue":"/sales"}}]
                  }]}]}]}
                """;

        var batch = parser.parse(otlp, Signal.LOGS, "retail-shop");

        assertThat(batch.logs()).hasSize(1);
        var row = batch.logs().get(0);
        assertThat(row.serviceName()).isEqualTo("retail-shop");
        assertThat(row.severity()).isEqualTo("ERROR");
        assertThat(row.severityNumber()).isEqualTo(17);
        assertThat(row.body()).contains("HikariPool-1");
        assertThat(row.traceId()).isEqualTo("abc123");
        assertThat(row.timestamp()).isEqualTo(Instant.ofEpochSecond(1753600000L));
        // resource attributes are denormalized onto the row alongside record attributes
        assertThat(row.attributes())
                .containsEntry("deployment.environment", "prod")
                .containsEntry("http.route", "/sales");
    }

    @Test
    void tenantIsStampedFromTheCallerNotThePayload() {
        // A tenant claiming another tenant's name in its own attributes must not win.
        String otlp = """
                {"resourceLogs":[{"resource":{"attributes":[
                    {"key":"service.name","value":{"stringValue":"evil"}},
                    {"key":"tenant_id","value":{"stringValue":"ember"}}]},
                  "scopeLogs":[{"logRecords":[{"body":{"stringValue":"x"}}]}]}]}
                """;

        var batch = parser.parse(otlp, Signal.LOGS, "retail-shop");

        assertThat(batch.logs().get(0).tenantId()).isEqualTo("retail-shop");
    }

    @Test
    void parsesGaugeAndSumMetricPoints() {
        String otlp = """
                {"resourceMetrics":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"retail-shop"}}]},
                  "scopeMetrics":[{"metrics":[
                    {"name":"db.pool.active","gauge":{"dataPoints":[
                        {"timeUnixNano":"1753600000000000000","asDouble":10.0}]}},
                    {"name":"http.requests","sum":{"dataPoints":[
                        {"timeUnixNano":"1753600001000000000","asInt":"42"}]}}
                  ]}]}]}
                """;

        var batch = parser.parse(otlp, Signal.METRICS, "retail-shop");

        assertThat(batch.metrics()).hasSize(2);
        assertThat(batch.metrics().get(0).metricName()).isEqualTo("db.pool.active");
        assertThat(batch.metrics().get(0).value()).isEqualTo(10.0);
        // proto3 JSON encodes 64-bit ints as strings — this must not silently become 0
        assertThat(batch.metrics().get(1).value()).isEqualTo(42.0);
    }

    @Test
    void parsesSpansAndComputesDuration() {
        String otlp = """
                {"resourceSpans":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"stringValue":"ember"}}]},
                  "scopeSpans":[{"spans":[{
                    "traceId":"t1","spanId":"s1","parentSpanId":"p1","name":"POST /orders",
                    "startTimeUnixNano":"1753600000000000000",
                    "endTimeUnixNano":"1753600000250000000",
                    "status":{"code":2}
                  }]}]}]}
                """;

        var batch = parser.parse(otlp, Signal.TRACES, "ember");

        assertThat(batch.spans()).hasSize(1);
        var span = batch.spans().get(0);
        assertThat(span.name()).isEqualTo("POST /orders");
        assertThat(span.durationNs()).isEqualTo(250_000_000L);
        assertThat(span.statusCode()).isEqualTo(2);
        assertThat(span.parentSpanId()).isEqualTo("p1");
    }

    @Test
    void acceptsSnakeCaseFieldNames() {
        String otlp = """
                {"resource_logs":[{
                  "resource":{"attributes":[{"key":"service.name","value":{"string_value":"ember"}}]},
                  "scope_logs":[{"log_records":[{
                    "time_unix_nano":"1753600000000000000",
                    "severity_text":"WARN",
                    "body":{"string_value":"disk almost full"}}]}]}]}
                """;

        var batch = parser.parse(otlp, Signal.LOGS, "ember");

        assertThat(batch.logs()).hasSize(1);
        assertThat(batch.logs().get(0).serviceName()).isEqualTo("ember");
        assertThat(batch.logs().get(0).severity()).isEqualTo("WARN");
    }

    @Test
    void emptyBatchYieldsNoRows() {
        assertThat(parser.parse("{\"resourceLogs\":[]}", Signal.LOGS, "ember").size()).isZero();
        assertThat(parser.parse("{}", Signal.METRICS, "ember").size()).isZero();
    }

    @Test
    void malformedJsonIsRejectedClearly() {
        assertThatThrownBy(() -> parser.parse("not json", Signal.LOGS, "ember"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void serviceNameFallsBackWhenAbsent() {
        String otlp = """
                {"resourceLogs":[{"scopeLogs":[{"logRecords":[{"body":{"stringValue":"orphan"}}]}]}]}
                """;
        assertThat(parser.parse(otlp, Signal.LOGS, "ember").logs().get(0).serviceName())
                .isEqualTo("unknown");
    }
}
