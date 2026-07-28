package ai.khukuri.ingest.auth;

import ai.khukuri.ingest.config.IngestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Exercises the real HTTP conversation with Identity. The controller tests stub this
 * verifier out entirely, so without these the OAuth2 wire format goes unverified — which
 * is exactly how a query-parameter token request (rejected with unsupported_grant_type)
 * reached a running system once.
 */
class IdentityKeyVerifierTest {

    private static final String IDENTITY = "http://identity.test";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private IdentityKeyVerifier verifier;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new IdentityKeyVerifier(
                new IngestProperties(
                        new IngestProperties.Identity(IDENTITY, "khukuri-gateway", "secret",
                                Duration.ofSeconds(60)),
                        new IngestProperties.Kafka("telemetry.raw"),
                        new IngestProperties.ClickHouse(100, Duration.ofSeconds(1))),
                builder);
    }

    private void expectTokenRequest() {
        server.expect(requestTo(IDENTITY + "/oauth2/token"))
                .andExpect(method(POST))
                // The regression this test exists for: parameters must be in a
                // form-encoded body, not the query string.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("scope=internal")))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    private void expectVerifyRequest(String responseJson) {
        server.expect(requestTo(IDENTITY + "/api/v1/keys/verify"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));
    }

    @Test
    void verifiesAnIngestKeyAndReturnsItsTenant() {
        expectTokenRequest();
        expectVerifyRequest("{\"valid\":true,\"tenant\":\"retail-shop\",\"type\":\"INGEST\"}");

        var tenant = verifier.verify("khk_ing_abc");

        assertThat(tenant).isPresent();
        assertThat(tenant.get().tenantSlug()).isEqualTo("retail-shop");
        server.verify();
    }

    @Test
    void rejectsAGatewayKeyPresentedToTheTelemetryEndpoint() {
        expectTokenRequest();
        // Identity says the key is genuine — but it is the wrong type for this service.
        expectVerifyRequest("{\"valid\":true,\"tenant\":\"retail-shop\",\"type\":\"GATEWAY\"}");

        assertThat(verifier.verify("khk_gw_abc")).isEmpty();
        server.verify();
    }

    @Test
    void rejectsAnUnknownKey() {
        expectTokenRequest();
        server.expect(requestTo(IDENTITY + "/api/v1/keys/verify"))
                .andRespond(withStatus(UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"valid\":false}"));

        assertThat(verifier.verify("khk_ing_bogus")).isEmpty();
    }

    @Test
    void cachesVerifiedKeysSoEveryBatchIsNotARoundTrip() {
        expectTokenRequest();
        expectVerifyRequest("{\"valid\":true,\"tenant\":\"ember\",\"type\":\"INGEST\"}");

        assertThat(verifier.verify("khk_ing_same")).isPresent();
        assertThat(verifier.verify("khk_ing_same")).isPresent();
        assertThat(verifier.verify("khk_ing_same")).isPresent();

        // Exactly one token call and one verify call were expected; a second verify
        // would fail this assertion.
        server.verify();
    }

    @Test
    void unreachableIdentityDegradesToRejectionRatherThanThrowing() {
        server.expect(requestTo(IDENTITY + "/oauth2/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(verifier.verify("khk_ing_abc")).isEmpty();
    }
}
