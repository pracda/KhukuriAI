package ai.khukuri.ingest.kafka;

import ai.khukuri.ingest.config.IngestProperties;
import ai.khukuri.ingest.model.TelemetryEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TelemetryProducer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final IngestProperties props;

    public TelemetryProducer(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                             IngestProperties props) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.props = props;
    }

    public void send(TelemetryEnvelope envelope) {
        try {
            // Partitioning by tenant keeps one tenant's batches ordered relative to
            // each other, which is what incident timelines need.
            kafka.send(props.kafka().topic(), envelope.tenantId(), mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            log.error("Could not serialize telemetry envelope for tenant {}", envelope.tenantId(), e);
            throw new IllegalStateException("Envelope serialization failed", e);
        }
    }
}
