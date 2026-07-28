package ai.khukuri.ingest.web;

import ai.khukuri.ingest.auth.IdentityKeyVerifier;
import ai.khukuri.ingest.kafka.TelemetryConsumer;
import ai.khukuri.ingest.kafka.TelemetryProducer;
import ai.khukuri.ingest.model.TelemetryEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression cover for gzip request bodies.
 *
 * <p>Every real OTLP sender compresses by default. Hand-written curl tests do not, which
 * is how an ingest service can pass its whole suite and still drop 100% of production
 * telemetry at the parser.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GzipRequestFilterTest {

    private static final String OTLP_BODY = """
            {"resourceLogs":[{"resource":{"attributes":[
                {"key":"service.name","value":{"stringValue":"pos-backend"}}]},
              "scopeLogs":[{"logRecords":[{"body":{"stringValue":"gzipped hello"}}]}]}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IdentityKeyVerifier verifier;

    @MockBean
    private TelemetryProducer producer;

    @MockBean
    private TelemetryConsumer consumer;

    @MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void gzippedOtlpBodyIsDecompressedBeforeItReachesTheEnvelope() throws Exception {
        when(verifier.verify(anyString())).thenReturn(
                Optional.of(new IdentityKeyVerifier.VerifiedTenant("retail-shop", "key-1")));

        mvc.perform(post("/v1/logs")
                        .header("X-Khukuri-Ingest-Key", "khk_ing_x")
                        .header("Content-Encoding", "gzip")
                        .contentType("application/json")
                        .content(gzip(OTLP_BODY)))
                .andExpect(status().isOk());

        ArgumentCaptor<TelemetryEnvelope> sent = ArgumentCaptor.forClass(TelemetryEnvelope.class);
        verify(producer).send(sent.capture());

        // What reaches Kafka must be readable JSON, not the compressed bytes.
        String payload = sent.getValue().decodedPayload();
        assertThat(payload).startsWith("{").contains("gzipped hello");
    }

    @Test
    void uncompressedBodiesStillWork() throws Exception {
        when(verifier.verify(anyString())).thenReturn(
                Optional.of(new IdentityKeyVerifier.VerifiedTenant("retail-shop", "key-1")));

        mvc.perform(post("/v1/logs")
                        .header("X-Khukuri-Ingest-Key", "khk_ing_x")
                        .contentType("application/json")
                        .content(OTLP_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<TelemetryEnvelope> sent = ArgumentCaptor.forClass(TelemetryEnvelope.class);
        verify(producer).send(sent.capture());
        assertThat(sent.getValue().decodedPayload()).contains("gzipped hello");
    }
}
