package ai.khukuri.incident.telemetry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read-only analytical queries over the telemetry ClickHouse tables. This is the surface
 * the ops-analyst agent reaches through its tools, so each method answers a question an
 * investigator would actually ask, rather than exposing raw SQL.
 *
 * <p>Tenant is always a bound parameter and never interpolated; callers pass a tenant
 * already resolved through {@code TenantAccess}.
 */
@Service
public class TelemetryQueryService {

    private final JdbcTemplate clickhouse;

    public TelemetryQueryService(@Qualifier("clickhouseJdbc") JdbcTemplate clickhouse) {
        this.clickhouse = clickhouse;
    }

    public record ErrorGroup(String serviceName, String sample, long count) {
    }

    public record MetricPoint(Instant timestamp, String serviceName, double value) {
    }

    public record ServiceHealth(String serviceName, long total, long errors, double errorRate) {
    }

    public record SlowSpan(String serviceName, String name, double p95Ms, long count) {
    }

    /** Most frequent error log bodies, grouped so one repeated failure reads as one line. */
    public List<ErrorGroup> errorLogs(String tenantId, int windowSeconds, String service, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT service_name, any(body) AS sample, count() AS c
                FROM logs
                WHERE tenant_id = ? AND timestamp > now() - toIntervalSecond(?)
                  AND severity_number >= 17
                """);
        if (service != null && !service.isBlank()) {
            sql.append(" AND service_name = ?");
        }
        // Group on a normalized prefix so the same error with varying ids collapses.
        sql.append("""
                 GROUP BY service_name, substring(body, 1, 80)
                 ORDER BY c DESC
                 LIMIT ?
                """);

        Object[] args = (service != null && !service.isBlank())
                ? new Object[]{tenantId, windowSeconds, service, limit}
                : new Object[]{tenantId, windowSeconds, limit};

        return clickhouse.query(sql.toString(), (rs, i) ->
                new ErrorGroup(rs.getString("service_name"), rs.getString("sample"), rs.getLong("c")), args);
    }

    /** Time series for one metric, bucketed so a long window stays readable. */
    public List<MetricPoint> metric(String tenantId, String metricName, int windowSeconds,
                                    String service, int bucketSeconds) {
        StringBuilder sql = new StringBuilder("""
                SELECT toStartOfInterval(timestamp, toIntervalSecond(?)) AS bucket,
                       service_name, avg(value) AS v
                FROM metrics
                WHERE tenant_id = ? AND metric_name = ? AND timestamp > now() - toIntervalSecond(?)
                """);
        if (service != null && !service.isBlank()) {
            sql.append(" AND service_name = ?");
        }
        sql.append(" GROUP BY bucket, service_name ORDER BY bucket ASC");

        Object[] args = (service != null && !service.isBlank())
                ? new Object[]{bucketSeconds, tenantId, metricName, windowSeconds, service}
                : new Object[]{bucketSeconds, tenantId, metricName, windowSeconds};

        return clickhouse.query(sql.toString(), (rs, i) -> new MetricPoint(
                rs.getTimestamp("bucket").toInstant(),
                rs.getString("service_name"),
                rs.getDouble("v")), args);
    }

    /** Error rate per service — the headline "is anything wrong?" query. */
    public List<ServiceHealth> serviceHealth(String tenantId, int windowSeconds) {
        String sql = """
                SELECT service_name,
                       count() AS total,
                       countIf(severity_number >= 17) AS errors
                FROM logs
                WHERE tenant_id = ? AND timestamp > now() - toIntervalSecond(?)
                GROUP BY service_name
                ORDER BY errors DESC
                """;
        return clickhouse.query(sql, (rs, i) -> {
            long total = rs.getLong("total");
            long errors = rs.getLong("errors");
            return new ServiceHealth(rs.getString("service_name"), total, errors,
                    total == 0 ? 0.0 : (double) errors / total);
        }, tenantId, windowSeconds);
    }

    /** p95 latency by span name — where time is going. */
    public List<SlowSpan> slowSpans(String tenantId, int windowSeconds, int limit) {
        String sql = """
                SELECT service_name, name,
                       quantile(0.95)(duration_ns) / 1e6 AS p95_ms,
                       count() AS c
                FROM spans
                WHERE tenant_id = ? AND timestamp > now() - toIntervalSecond(?)
                GROUP BY service_name, name
                ORDER BY p95_ms DESC
                LIMIT ?
                """;
        return clickhouse.query(sql, (rs, i) -> new SlowSpan(
                rs.getString("service_name"),
                rs.getString("name"),
                rs.getDouble("p95_ms"),
                rs.getLong("c")), tenantId, windowSeconds, limit);
    }

    /** Latest value of a metric per service — used by the saturation detector. */
    public Map<String, Double> latestMetricByService(String tenantId, String metricName,
                                                     int windowSeconds) {
        String sql = """
                SELECT service_name, argMax(value, timestamp) AS latest
                FROM metrics
                WHERE tenant_id = ? AND metric_name = ? AND timestamp > now() - toIntervalSecond(?)
                GROUP BY service_name
                """;
        return clickhouse.query(sql, rs -> {
            Map<String, Double> out = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("service_name"), rs.getDouble("latest"));
            }
            return out;
        }, tenantId, metricName, windowSeconds);
    }

    /** p95 span latency per service, for the latency detector. */
    public Map<String, LatencyStat> latencyByService(String tenantId, int windowSeconds) {
        String sql = """
                SELECT service_name,
                       quantile(0.95)(duration_ns) / 1e6 AS p95_ms,
                       count() AS c
                FROM spans
                WHERE tenant_id = ? AND timestamp > now() - toIntervalSecond(?)
                GROUP BY service_name
                """;
        return clickhouse.query(sql, rs -> {
            Map<String, LatencyStat> out = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("service_name"),
                        new LatencyStat(rs.getDouble("p95_ms"), rs.getLong("c")));
            }
            return out;
        }, tenantId, windowSeconds);
    }

    public record LatencyStat(double p95Ms, long count) {
    }

    /** Tenants that have shipped telemetry recently — what the detector sweeps. */
    public List<String> activeTenants(int windowSeconds) {
        return clickhouse.queryForList("""
                SELECT DISTINCT tenant_id FROM logs
                WHERE timestamp > now() - toIntervalSecond(?)
                """, String.class, windowSeconds);
    }
}
