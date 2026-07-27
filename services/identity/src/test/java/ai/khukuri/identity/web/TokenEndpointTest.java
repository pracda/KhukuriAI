package ai.khukuri.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void gatewayClientGetsServiceTokenViaClientCredentials() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString("khukuri-gateway:test-gateway-secret".getBytes());

        mvc.perform(post("/oauth2/token")
                        .header("Authorization", "Basic " + basic)
                        .param("grant_type", "client_credentials")
                        .param("scope", "internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void wrongClientSecretIsRejected() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString("khukuri-gateway:wrong".getBytes());

        mvc.perform(post("/oauth2/token")
                        .header("Authorization", "Basic " + basic)
                        .param("grant_type", "client_credentials")
                        .param("scope", "internal"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oidcDiscoveryIsPublished() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8181"))
                .andExpect(jsonPath("$.token_endpoint").isNotEmpty());
    }
}
