package ai.khukuri.identity.service;

import ai.khukuri.identity.domain.ApiKey;
import ai.khukuri.identity.domain.Tenant;
import ai.khukuri.identity.repository.ApiKeyRepository;
import ai.khukuri.identity.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeys;
    private final TenantRepository tenants;

    public ApiKeyService(ApiKeyRepository apiKeys, TenantRepository tenants) {
        this.apiKeys = apiKeys;
        this.tenants = tenants;
    }

    /** The issued key, returned exactly once. Only its hash is stored. */
    public record IssuedKey(UUID id, String key, String prefix, ApiKey.KeyType type, String tenantSlug) {
    }

    public record VerifiedKey(String tenantSlug, ApiKey.KeyType type, UUID keyId) {
    }

    @Transactional
    public IssuedKey issue(String tenantSlug, ApiKey.KeyType type, String label) {
        Tenant tenant = tenants.findBySlug(tenantSlug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + tenantSlug));

        String raw = generateKey(type);
        String prefix = raw.substring(0, 12);
        ApiKey entity = new ApiKey(tenant.getId(), type, prefix, sha256(raw), label);
        apiKeys.save(entity);
        return new IssuedKey(entity.getId(), raw, prefix, type, tenantSlug);
    }

    @Transactional(readOnly = true)
    public Optional<VerifiedKey> verify(String rawKey) {
        return apiKeys.findByKeyHash(sha256(rawKey))
                .filter(ApiKey::isActive)
                .flatMap(key -> tenants.findById(key.getTenantId())
                        .map(tenant -> new VerifiedKey(tenant.getSlug(), key.getKeyType(), key.getId())));
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listForTenant(String tenantSlug) {
        Tenant tenant = tenants.findBySlug(tenantSlug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + tenantSlug));
        return apiKeys.findByTenantIdOrderByCreatedAtDesc(tenant.getId());
    }

    @Transactional
    public void revoke(UUID keyId) {
        apiKeys.findById(keyId).ifPresent(ApiKey::revoke);
    }

    private static String generateKey(ApiKey.KeyType type) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String typeTag = switch (type) {
            case INGEST -> "ing";
            case GATEWAY -> "gw";
        };
        return "khk_" + typeTag + "_" + body;
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
