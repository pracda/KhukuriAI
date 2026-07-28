package ai.khukuri.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(Identity identity, Kafka kafka, ClickHouse clickhouse) {

    /**
     * How this service authenticates tenant ingest keys: it holds client-credentials
     * for the Identity service and calls the internal verify endpoint.
     */
    public record Identity(
            String url,
            String clientId,
            String clientSecret,
            Duration cacheTtl) {
    }

    public record Kafka(String topic) {
    }

    public record ClickHouse(int batchSize, Duration flushInterval) {
    }
}
