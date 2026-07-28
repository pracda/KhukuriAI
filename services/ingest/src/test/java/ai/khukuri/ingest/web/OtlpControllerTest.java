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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtlpControllerTest {

    private static final String KEY_HEADER = "X-Khukuri-Ingest-Key";
    private static final String OTLP_BODY = """
            {"resourceLogs":[{"resource":{"attributes":[
                {"key":"service.name","value":{"stringValue":"retail-shop"}}]},
              "scopeLogs":[{"logRecords":[{"body":{"stringValue":"hello"}}]}]}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IdentityKeyVerifier verifier;

    @MockBean
    private TelemetryProducer producer;

    @MockBean
    private TelemetryConsumer consumer;

    /** ClickHouse is a collaborator here, not a subject — no container needed. */
    @MockBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void keyIsValid() {
        when(verifier.verify(anyString())).thenReturn(
                Optional.of(new IdentityKeyVerifier.VerifiedTenant("retail-shop", "key-abc")));
    }

    @Test
    void acceptsAuthenticatedLogsAndProducesEnvelope() throws Exception {
        keyIsValid();

        mvc.perform(post("/v1/logs")
                        .header(KEY_HEADER, "khk_ing_something")
                        .contentType("application/json")
                        .content(OTLP_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<TelemetryEnvelope> sent = ArgumentCaptor.forClass(TelemetryEnvelope.class);
        verify(producer).send(sent.capture());

        TelemetryEnvelope envelope = sent.getValue();
        assertThat(envelope.envelopeVersion()).isEqualTo(1);
        assertThat(envelope.tenantId()).isEqualTo("retail-shop");
        assertThat(envelope.signal()).isEqualTo("logs");
        assertThat(envelope.encoding()).isEqualTo("json");
        assertThat(envelope.ingestKeyId()).isEqualTo("key-abc");
        // the payload round-trips unmodified — the receiver does not parse it
        assertThat(envelope.decodedPayload()).contains("hello");
    }

    @Test
    void rejectsMissingKey() throws Exception {
        mvc.perform(post("/v1/logs")
                        .contentType("application/json")
                        .content(OTLP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing " + KEY_HEADER));

        verify(producer, never()).send(any());
    }

    @Test
    void rejectsUnknownOrRevokedKey() throws Exception {
        when(verifier.verify(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/v1/logs")
                        .header(KEY_HEADER, "khk_ing_revoked")
                        .contentType("application/json")
                        .content(OTLP_BODY))
                .andExpect(status().isUnauthorized());

        verify(producer, never()).send(any());
    }

    @Test
    void rejectsEmptyBody() throws Exception {
        keyIsValid();

        mvc.perform(post("/v1/metrics")
                        .header(KEY_HEADER, "khk_ing_something")
                        .contentType("application/json")
                        .content(""))
                .andExpect(status().isBadRequest());

        verify(producer, never()).send(any());
    }

    @Test
    void protobufIsRejectedWithAnActionableMessage() throws Exception {
        keyIsValid();

        mvc.perform(post("/v1/traces")
                        .header(KEY_HEADER, "khk_ing_something")
                        .contentType("application/x-protobuf")
                        .content(new byte[]{0x0a, 0x02}))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("encoding: json")));
    }

    @Test
    void allThreeSignalsAreAccepted() throws Exception {
        keyIsValid();

        for (String path : new String[]{"/v1/logs", "/v1/metrics", "/v1/traces"}) {
            mvc.perform(post(path)
                            .header(KEY_HEADER, "khk_ing_something")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }
}
