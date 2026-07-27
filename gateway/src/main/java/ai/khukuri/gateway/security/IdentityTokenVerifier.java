package ai.khukuri.gateway.security;

import ai.khukuri.gateway.config.IdentityFederationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Verifies platform (Khukuri Identity) RS256 tokens against Identity's JWKS. The decoder is
 * built lazily on first use so the gateway boots — and legacy auth keeps working — even when
 * Identity is unreachable or federation is disabled.
 */
@Slf4j
@Component
public class IdentityTokenVerifier {

    private final IdentityFederationProperties props;
    private volatile JwtDecoder decoder;

    public IdentityTokenVerifier(IdentityFederationProperties props) {
        this.props = props;
    }

    /**
     * @param username the token subject
     * @param role     mapped gateway role: ADMIN for khukuri:owner/admin grants, else USER
     * @param grants   raw platform "tenant:role" grants, for auditing
     */
    public record FederatedUser(String username, String role, List<String> grants) {
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    public Optional<FederatedUser> verify(String token) {
        if (!props.isEnabled()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = jwtDecoder().decode(token);
            List<String> grants = jwt.getClaimAsStringList("roles");
            if (grants == null) {
                grants = List.of();
            }
            String role = grants.contains("khukuri:owner") || grants.contains("khukuri:admin")
                    ? "ADMIN" : "USER";
            return Optional.of(new FederatedUser(jwt.getSubject(), role, grants));
        } catch (Exception e) {
            // Broad on purpose: an unreachable Identity (JWKS fetch failure) must degrade to
            // "not a platform token", never break the legacy auth path.
            log.debug("Not a valid platform token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                if (decoder == null) {
                    decoder = buildDecoder();
                }
                local = decoder;
            }
        }
        return local;
    }

    /** Overridable for tests (local key material instead of a live JWKS endpoint). */
    protected JwtDecoder buildDecoder() {
        NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(props.resolveJwksUri()).build();
        built.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.getIssuer()));
        return built;
    }
}
