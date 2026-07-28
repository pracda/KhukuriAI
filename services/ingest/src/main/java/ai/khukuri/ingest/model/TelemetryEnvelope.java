package ai.khukuri.ingest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The {@code telemetry.raw} envelope — see
 * {@code contracts/events/telemetry.raw.v1.schema.json}. Field names are snake_case on
 * the wire to match the contract exactly.
 *
 * <p>{@code tenantId} is stamped from the authenticated ingest key, never read from the
 * payload: a tenant cannot claim to be another tenant by crafting its OTLP body.
 */
public record TelemetryEnvelope(
        @JsonProperty("envelope_version") int envelopeVersion,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("signal") String signal,
        @JsonProperty("encoding") String encoding,
        @JsonProperty("received_at") Instant receivedAt,
        @JsonProperty("ingest_key_id") String ingestKeyId,
        @JsonProperty("payload") String payload) {

    public static final int CURRENT_VERSION = 1;
    public static final String ENCODING_JSON = "json";

    public static TelemetryEnvelope of(String tenantId, Signal signal, String ingestKeyId, byte[] body) {
        return new TelemetryEnvelope(
                CURRENT_VERSION,
                tenantId,
                signal.wireName(),
                ENCODING_JSON,
                Instant.now(),
                ingestKeyId,
                Base64.getEncoder().encodeToString(body));
    }

    public String decodedPayload() {
        return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
    }
}
