package ai.khukuri.gateway.security;

import ai.khukuri.gateway.config.IdentityFederationProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityTokenVerifierTest {

    private static final String ISSUER = "http://identity.test";
    private static RSAKey rsaKey;

    @BeforeAll
    static void generateKey() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    }

    /** Verifier wired to local key material instead of a live JWKS endpoint. */
    private static IdentityTokenVerifier verifier(boolean enabled) {
        IdentityFederationProperties props = new IdentityFederationProperties();
        props.setEnabled(enabled);
        props.setIssuer(ISSUER);
        return new IdentityTokenVerifier(props) {
            @Override
            protected JwtDecoder buildDecoder() {
                try {
                    NimbusJwtDecoder decoder = NimbusJwtDecoder
                            .withPublicKey(rsaKey.toRSAPublicKey()).build();
                    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
                    return decoder;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static String token(String subject, String issuer, List<String> roles, long ttlMillis)
            throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + ttlMillis));
        if (roles != null) {
            claims.claim("roles", roles);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                claims.build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    @Test
    void platformOwnerMapsToAdmin() throws Exception {
        var user = verifier(true)
                .verify(token("prasiddha", ISSUER, List.of("khukuri:owner"), 60_000));
        assertThat(user).isPresent();
        assertThat(user.get().username()).isEqualTo("prasiddha");
        assertThat(user.get().role()).isEqualTo("ADMIN");
    }

    @Test
    void tenantRoleMapsToUser() throws Exception {
        var user = verifier(true)
                .verify(token("cashier", ISSUER, List.of("retail-shop:analyst"), 60_000));
        assertThat(user).isPresent();
        assertThat(user.get().role()).isEqualTo("USER");
        assertThat(user.get().grants()).containsExactly("retail-shop:analyst");
    }

    @Test
    void missingRolesClaimStillAuthenticatesAsUser() throws Exception {
        var user = verifier(true).verify(token("norole", ISSUER, null, 60_000));
        assertThat(user).isPresent();
        assertThat(user.get().role()).isEqualTo("USER");
        assertThat(user.get().grants()).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        var user = verifier(true)
                .verify(token("late", ISSUER, List.of("khukuri:owner"), -60_000));
        assertThat(user).isEmpty();
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        var user = verifier(true)
                .verify(token("spoof", "http://evil.test", List.of("khukuri:owner"), 60_000));
        assertThat(user).isEmpty();
    }

    @Test
    void disabledFederationVerifiesNothing() throws Exception {
        var user = verifier(false)
                .verify(token("prasiddha", ISSUER, List.of("khukuri:owner"), 60_000));
        assertThat(user).isEmpty();
    }

    @Test
    void garbageIsRejectedNotThrown() {
        assertThat(verifier(true).verify("not-a-jwt")).isEmpty();
    }
}
