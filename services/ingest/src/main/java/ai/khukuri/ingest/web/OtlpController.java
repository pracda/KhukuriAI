package ai.khukuri.ingest.web;

import ai.khukuri.ingest.auth.IdentityKeyVerifier;
import ai.khukuri.ingest.kafka.TelemetryProducer;
import ai.khukuri.ingest.model.Signal;
import ai.khukuri.ingest.model.TelemetryEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The OTLP/HTTP receive surface — the standard OTLP paths, so any OpenTelemetry SDK or
 * Collector can ship here with no custom exporter.
 *
 * <p>The body is <b>not</b> parsed here: it is authenticated, wrapped in a
 * {@link TelemetryEnvelope}, and handed to Kafka. Parsing happens on the consumer side,
 * which keeps the receive path fast and makes bad payloads replayable rather than lost.
 *
 * <p>v0 accepts OTLP/JSON only. Protobuf bodies are rejected with 415 rather than silently
 * dropped — see the service README for the upgrade path.
 */
@RestController
public class OtlpController {

    private static final Logger log = LoggerFactory.getLogger(OtlpController.class);
    private static final String KEY_HEADER = "X-Khukuri-Ingest-Key";

    private final IdentityKeyVerifier verifier;
    private final TelemetryProducer producer;

    public OtlpController(IdentityKeyVerifier verifier, TelemetryProducer producer) {
        this.verifier = verifier;
        this.producer = producer;
    }

    @PostMapping(path = "/v1/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> logs(@RequestHeader(name = KEY_HEADER, required = false) String key,
                                  @RequestBody byte[] body) {
        return accept(Signal.LOGS, key, body);
    }

    @PostMapping(path = "/v1/metrics", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> metrics(@RequestHeader(name = KEY_HEADER, required = false) String key,
                                     @RequestBody byte[] body) {
        return accept(Signal.METRICS, key, body);
    }

    @PostMapping(path = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> traces(@RequestHeader(name = KEY_HEADER, required = false) String key,
                                    @RequestBody byte[] body) {
        return accept(Signal.TRACES, key, body);
    }

    /** Protobuf is the OTLP default, so say plainly why it bounced instead of 404ing. */
    @PostMapping(path = {"/v1/logs", "/v1/metrics", "/v1/traces"},
            consumes = "application/x-protobuf")
    public ResponseEntity<?> protobufNotSupported() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                "error", "OTLP/protobuf is not supported yet — configure the exporter with encoding: json"));
    }

    private ResponseEntity<?> accept(Signal signal, String key, byte[] body) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing " + KEY_HEADER));
        }
        Optional<IdentityKeyVerifier.VerifiedTenant> tenant = verifier.verify(key);
        if (tenant.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or revoked ingest key"));
        }
        if (body == null || body.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty body"));
        }

        TelemetryEnvelope envelope = TelemetryEnvelope.of(
                tenant.get().tenantSlug(), signal, tenant.get().keyId(), body);
        producer.send(envelope);

        log.debug("Accepted {} batch: tenant={} bytes={}",
                signal.wireName(), tenant.get().tenantSlug(), body.length);
        // OTLP/HTTP expects an empty JSON object as the success response.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new String("{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
