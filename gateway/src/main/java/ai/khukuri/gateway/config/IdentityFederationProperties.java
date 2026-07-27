package ai.khukuri.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Federation with the Khukuri Identity service — {@code identity.federation.*}. When enabled,
 * bearer tokens that fail local (HS256) validation are additionally checked against Identity's
 * JWKS as platform-issued RS256 tokens, so one OIDC login works across the whole platform.
 *
 * <p>OFF by default: the deployed gateway keeps its standalone auth untouched until an Identity
 * instance actually exists next to it. Platform roles map onto gateway roles — {@code
 * khukuri:owner} / {@code khukuri:admin} become ADMIN, any other grant becomes USER. See
 * ADR-008 for the full transition plan (this is phase A; platform API keys are phase B).
 */
@Data
@Component
@ConfigurationProperties(prefix = "identity.federation")
public class IdentityFederationProperties {

    private boolean enabled = false;

    /** Expected {@code iss} claim, e.g. http://localhost:8181. */
    private String issuer = "http://localhost:8181";

    /** Defaults to {@code <issuer>/oauth2/jwks} when unset. */
    private String jwksUri;

    public String resolveJwksUri() {
        return StringUtils.hasText(jwksUri) ? jwksUri : issuer + "/oauth2/jwks";
    }
}
