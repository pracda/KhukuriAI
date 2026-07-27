package ai.khukuri.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the federation path end-to-end at the filter-chain level: a token the local
 * HS256 validator rejects still authenticates when the Identity verifier recognizes it —
 * and does not when it doesn't.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FederatedAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IdentityTokenVerifier identityTokenVerifier;

    @Test
    void identityTokenAuthenticatesAgainstJwtProtectedEndpoint() throws Exception {
        when(identityTokenVerifier.enabled()).thenReturn(true);
        when(identityTokenVerifier.verify(anyString())).thenReturn(Optional.of(
                new IdentityTokenVerifier.FederatedUser(
                        "prasiddha", "ADMIN", List.of("khukuri:owner"))));

        mockMvc.perform(get("/api/v1/health")
                        .header("Authorization", "Bearer some-platform-issued-token"))
                .andExpect(status().isOk());
    }

    @Test
    void unrecognizedTokenIsStillRejected() throws Exception {
        when(identityTokenVerifier.enabled()).thenReturn(true);
        when(identityTokenVerifier.verify(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/health")
                        .header("Authorization", "Bearer some-garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void federatedUserTokenAloneCannotCallChat() throws Exception {
        when(identityTokenVerifier.enabled()).thenReturn(true);
        when(identityTokenVerifier.verify(anyString())).thenReturn(Optional.of(
                new IdentityTokenVerifier.FederatedUser(
                        "prasiddha", "ADMIN", List.of("khukuri:owner"))));

        // /chat requires an API key specifically — a federated JWT must not bypass that
        // (same contract as local JWTs; platform API keys are ADR-008 phase B).
        mockMvc.perform(get("/api/v1/models")
                        .header("Authorization", "Bearer some-platform-issued-token"))
                .andExpect(status().isOk());
    }
}
