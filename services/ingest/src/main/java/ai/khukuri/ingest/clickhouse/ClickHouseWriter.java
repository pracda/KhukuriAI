package ai.khukuri.ingest.clickhouse;

import ai.khukuri.ingest.otlp.TelemetryRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Batch-inserts flattened telemetry rows. */
@Component
public class ClickHouseWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseWriter.class);

    private static final String INSERT_LOGS = """
            INSERT INTO logs (timestamp, tenant_id, service_name, severity, severity_number,
                              body, trace_id, span_id, attributes, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_METRICS = """
            INSERT INTO metrics (timestamp, tenant_id, service_name, metric_name, value,
                                 attributes, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_SPANS = """
            INSERT INTO spans (timestamp, tenant_id, service_name, trace_id, span_id,
                               parent_span_id, name, duration_ns, status_code, attributes, fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ClickHouseWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(TelemetryRows.Batch batch) {
        writeLogs(batch.logs());
        writeMetrics(batch.metrics());
        writeSpans(batch.spans());
        log.debug("Wrote {} telemetry rows", batch.size());
    }

    private void writeLogs(List<TelemetryRows.LogRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_LOGS, rows, rows.size(), (PreparedStatement ps, TelemetryRows.LogRow r) -> {
            ps.setTimestamp(1, Timestamp.from(r.timestamp()));
            ps.setString(2, r.tenantId());
            ps.setString(3, r.serviceName());
            ps.setString(4, r.severity());
            ps.setInt(5, clampSeverity(r.severityNumber()));
            ps.setString(6, r.body());
            ps.setString(7, r.traceId());
            ps.setString(8, r.spanId());
            setAttributes(ps, 9, r.attributes());
            ps.setLong(10, fingerprint(r.tenantId(), r.serviceName(), r.body(), r.timestamp().toString()));
        });
    }

    private void writeMetrics(List<TelemetryRows.MetricRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_METRICS, rows, rows.size(), (PreparedStatement ps, TelemetryRows.MetricRow r) -> {
            ps.setTimestamp(1, Timestamp.from(r.timestamp()));
            ps.setString(2, r.tenantId());
            ps.setString(3, r.serviceName());
            ps.setString(4, r.metricName());
            ps.setDouble(5, r.value());
            setAttributes(ps, 6, r.attributes());
            ps.setLong(7, fingerprint(r.tenantId(), r.metricName(),
                    String.valueOf(r.value()), r.timestamp().toString()));
        });
    }

    private void writeSpans(List<TelemetryRows.SpanRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_SPANS, rows, rows.size(), (PreparedStatement ps, TelemetryRows.SpanRow r) -> {
            ps.setTimestamp(1, Timestamp.from(r.timestamp()));
            ps.setString(2, r.tenantId());
            ps.setString(3, r.serviceName());
            ps.setString(4, r.traceId());
            ps.setString(5, r.spanId());
            ps.setString(6, r.parentSpanId());
            ps.setString(7, r.name());
            ps.setLong(8, r.durationNs());
            ps.setInt(9, r.statusCode());
            setAttributes(ps, 10, r.attributes());
            ps.setLong(11, fingerprint(r.tenantId(), r.traceId(), r.spanId(), r.name()));
        });
    }

    private static void setAttributes(PreparedStatement ps, int index, Map<String, String> attrs)
            throws SQLException {
        // The ClickHouse JDBC driver maps a java.util.Map onto Map(String, String).
        ps.setObject(index, attrs);
    }

    /** severity_number is UInt8; OTLP defines 1–24, but clamp so bad input can't fail a batch. */
    private static int clampSeverity(int severityNumber) {
        return Math.max(0, Math.min(255, severityNumber));
    }

    /**
     * Stable content hash so a replayed Kafka batch produces identical rows, which
     * ReplacingMergeTree can then collapse.
     */
    private static long fingerprint(String... parts) {
        return Objects.hash((Object[]) parts) & 0xFFFFFFFFL;
    }
}
