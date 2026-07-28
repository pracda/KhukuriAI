package ai.khukuri.ingest.clickhouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Creates the telemetry tables if they are absent.
 *
 * <p>ClickHouse has no Flyway equivalent in this stack, and the DDL is idempotent, so
 * schema creation runs on boot. Once the schema starts evolving, this is the seam where
 * a versioned migration runner goes.
 *
 * <p>Engine choice: {@code ReplacingMergeTree} with a fingerprint in the sort key, so the
 * at-least-once Kafka path collapses replayed duplicates on merge. Partitioning by day
 * makes retention a partition drop rather than a delete.
 */
@Configuration
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final List<String> DDL = List.of("""
            CREATE TABLE IF NOT EXISTS logs (
                timestamp        DateTime64(9),
                tenant_id        LowCardinality(String),
                service_name     LowCardinality(String),
                severity         LowCardinality(String),
                severity_number  UInt8,
                body             String,
                trace_id         String,
                span_id          String,
                attributes       Map(String, String),
                fingerprint      UInt64
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toDate(timestamp)
            ORDER BY (tenant_id, service_name, timestamp, fingerprint)
            """, """
            CREATE TABLE IF NOT EXISTS metrics (
                timestamp     DateTime64(9),
                tenant_id     LowCardinality(String),
                service_name  LowCardinality(String),
                metric_name   LowCardinality(String),
                value         Float64,
                attributes    Map(String, String),
                fingerprint   UInt64
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toDate(timestamp)
            ORDER BY (tenant_id, metric_name, timestamp, fingerprint)
            """, """
            CREATE TABLE IF NOT EXISTS spans (
                timestamp       DateTime64(9),
                tenant_id       LowCardinality(String),
                service_name    LowCardinality(String),
                trace_id        String,
                span_id         String,
                parent_span_id  String,
                name            String,
                duration_ns     UInt64,
                status_code     UInt8,
                attributes      Map(String, String),
                fingerprint     UInt64
            ) ENGINE = ReplacingMergeTree
            PARTITION BY toDate(timestamp)
            ORDER BY (tenant_id, service_name, timestamp, fingerprint)
            """);

    @Bean
    public ApplicationRunner initTelemetrySchema(JdbcTemplate jdbc) {
        return args -> {
            for (String ddl : DDL) {
                jdbc.execute(ddl);
            }
            log.info("ClickHouse telemetry schema ready (logs, metrics, spans)");
        };
    }
}
