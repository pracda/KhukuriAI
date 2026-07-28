package ai.khukuri.ingest.auth;

import ai.khukuri.ingest.config.IngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies tenant ingest keys against the Identity service.
 *
 * <p>Every OTLP batch carries a key, so a network round-trip per batch would dominate
 * ingest latency; verified results are cached for a short TTL, keyed by a hash of the
 * key (never the key itself). Revocation therefore takes effect within one TTL — a
 * deliberate, documented tradeoff.
 */
@Component
public class IdentityKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(IdentityKeyVerifier.class);
    private static final String REQUIRED_KEY_TYPE = "INGEST";

    private final IngestProperties props;
    private final RestClient http;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private volatile String serviceToken;
    private volatile Instant serviceTokenExpiry = Instant.EPOCH;

    public IdentityKeyVerifier(IngestProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    public record VerifiedTenant(String tenantSlug, String keyId) {
    }

    private record CacheEntry(VerifiedTenant tenant, Instant expiresAt) {
    }

    public Optional<VerifiedTenant> verify(String rawKey) {
        String cacheKey = sha256(rawKey);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cached.tenant());
        }

        Optional<VerifiedTenant> verified = callIdentity(rawKey);
        verified.ifPresent(tenant -> cache.put(cacheKey,
                new CacheEntry(tenant, Instant.now().plus(props.identity().cacheTtl()))));
        return verified;
    }

    private Optional<VerifiedTenant> callIdentity(String rawKey) {
        try {
            JsonNode response = http.post()
                    .uri(props.identity().url() + "/api/v1/keys/verify")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("key", rawKey))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("valid").asBoolean(false)) {
                return Optional.empty();
            }
            // A gateway key must not be usable to ship telemetry, and vice versa —
            // Identity tells us the type, and this is where that boundary is enforced.
            String type = response.path("type").asText("");
            if (!REQUIRED_KEY_TYPE.equals(type)) {
                log.warn("Rejected a valid {} key presented to the telemetry endpoint", type);
                return Optional.empty();
            }
            return Optional.of(new VerifiedTenant(
                    response.path("tenant").asText(),
                    response.path("keyId").asText(fingerprint(rawKey))));
        } catch (Exception e) {
            // Includes the 401 Identity returns for an unknown key — not an error worth
            // a stack trace, but a genuinely unreachable Identity must be visible.
            log.debug("Key verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Client-credentials token for the internal verify endpoint, refreshed on expiry. */
    private String serviceToken() {
        if (serviceToken != null && serviceTokenExpiry.isAfter(Instant.now().plusSeconds(30))) {
            return serviceToken;
        }
        synchronized (this) {
            if (serviceToken != null && serviceTokenExpiry.isAfter(Instant.now().plusSeconds(30))) {
                return serviceToken;
            }
            String basic = Base64.getEncoder().encodeToString(
                    (props.identity().clientId() + ":" + props.identity().clientSecret())
                            .getBytes(StandardCharsets.UTF_8));
            // RFC 6749 §4.4.2: token request parameters go in a form-encoded body.
            // Passing them as query parameters gets rejected with unsupported_grant_type.
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("scope", "internal");
            JsonNode token = http.post()
                    .uri(props.identity().url() + "/oauth2/token")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (token == null || !token.hasNonNull("access_token")) {
                throw new IllegalStateException("Identity returned no access token");
            }
            serviceToken = token.get("access_token").asText();
            serviceTokenExpiry = Instant.now().plusSeconds(token.path("expires_in").asLong(300));
            return serviceToken;
        }
    }

    private static String fingerprint(String rawKey) {
        return sha256(rawKey).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
