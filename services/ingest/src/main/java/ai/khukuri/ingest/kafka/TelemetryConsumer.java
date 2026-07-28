package ai.khukuri.ingest.kafka;

import ai.khukuri.ingest.clickhouse.ClickHouseWriter;
import ai.khukuri.ingest.model.Signal;
import ai.khukuri.ingest.model.TelemetryEnvelope;
import ai.khukuri.ingest.otlp.OtlpJsonParser;
import ai.khukuri.ingest.otlp.TelemetryRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Consumes {@code telemetry.raw}, parses the OTLP payload, and writes rows to ClickHouse.
 *
 * <p>Delivery is at-least-once: offsets commit after a successful write, so a crash
 * mid-batch replays it. Duplicate rows are collapsed by ClickHouse's ReplacingMergeTree
 * on merge (eventually, not immediately — see the service README).
 *
 * <p>A payload that cannot be parsed is logged and skipped rather than retried forever;
 * one malformed batch must not stall a tenant's telemetry.
 */
@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final ObjectMapper mapper;
    private final OtlpJsonParser parser;
    private final ClickHouseWriter writer;

    public TelemetryConsumer(ObjectMapper mapper, OtlpJsonParser parser, ClickHouseWriter writer) {
        this.mapper = mapper;
        this.parser = parser;
        this.writer = writer;
    }

    @KafkaListener(topics = "${ingest.kafka.topic}", groupId = "khukuri-ingest-writer")
    public void consume(List<String> messages) {
        for (String message : messages) {
            try {
                TelemetryEnvelope envelope = mapper.readValue(message, TelemetryEnvelope.class);
                Signal signal = Signal.valueOf(envelope.signal().toUpperCase(Locale.ROOT));
                TelemetryRows.Batch batch = parser.parse(
                        envelope.decodedPayload(), signal, envelope.tenantId());
                if (batch.size() > 0) {
                    writer.write(batch);
                }
            } catch (Exception e) {
                log.warn("Skipping unparseable telemetry envelope: {}", e.getMessage());
            }
        }
    }
}
