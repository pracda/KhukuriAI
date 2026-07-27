package ai.khukuri.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor platformAdmin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceToken() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_internal"));
    }

    @Test
    void tenantListRequiresPlatformAdmin() throws Exception {
        mvc.perform(get("/api/v1/tenants").with(jwt()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/tenants").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug").value(org.hamcrest.Matchers.hasItem("retail-shop")));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createTenantValidatesSlug() throws Exception {
        mvc.perform(post("/api/v1/tenants").with(platformAdmin())
                        .contentType("application/json")
                        .content("{\"slug\":\"Bad Slug!\",\"displayName\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issueThenVerifyKeyRoundTrip() throws Exception {
        var result = mvc.perform(post("/api/v1/tenants/retail-shop/keys").with(platformAdmin())
                        .contentType("application/json")
                        .content("{\"type\":\"GATEWAY\",\"label\":\"pos terminal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").isNotEmpty())
                .andExpect(jsonPath("$.tenant").value("retail-shop"))
                .andReturn();

        String key = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.key");

        // A platform service verifies the key with an internal-scope service token.
        mvc.perform(post("/api/v1/keys/verify").with(serviceToken())
                        .contentType("application/json")
                        .content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.tenant").value("retail-shop"))
                .andExpect(jsonPath("$.type").value("GATEWAY"));

        // A user token — even platform admin — cannot call verify.
        mvc.perform(post("/api/v1/keys/verify").with(platformAdmin())
                        .contentType("application/json")
                        .content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidKeyReturns401() throws Exception {
        mvc.perform(post("/api/v1/keys/verify").with(serviceToken())
                        .contentType("application/json")
                        .content("{\"key\":\"khk_gw_bogus\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.valid").value(false));
    }
}
