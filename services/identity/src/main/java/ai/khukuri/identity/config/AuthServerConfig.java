package ai.khukuri.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration
public class AuthServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthServerConfig.class);

    @Bean
    public RegisteredClientRepository registeredClientRepository(IdentityProperties props) {
        // Public client for Khukuri Desktop: Authorization Code + PKCE, no secret.
        RegisteredClient desktop = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("khukuri-desktop")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(props.desktopRedirectUris()))
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        // Confidential client for the gateway: client-credentials, "internal" scope
        // (used to call POST /api/v1/keys/verify).
        RegisteredClient gateway = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("khukuri-gateway")
                .clientSecret("{noop}" + props.clients().gatewaySecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal")
                .build();

        return new InMemoryRegisteredClientRepository(desktop, gateway);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            if (context.getPrincipal() instanceof UsernamePasswordAuthenticationToken user) {
                List<String> roles = user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList();
                context.getClaims().claim("roles", roles);
            } else {
                // Client-credentials: mark the token as a service token.
                context.getClaims().claim("svc", context.getRegisteredClient().getClientId());
            }
        };
    }

    /**
     * Dev key material: a fresh RSA key per boot, meaning all tokens are invalidated on
     * restart. Deliberate v0 tradeoff — persistent keys (env-provided PEM) are a
     * hardening item before any external user exists.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            log.warn("Generated ephemeral RSA signing key — tokens will not survive a restart (dev mode)");
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate RSA key", e);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(IdentityProperties props) {
        return AuthorizationServerSettings.builder()
                .issuer(props.issuer())
                .build();
    }
}
