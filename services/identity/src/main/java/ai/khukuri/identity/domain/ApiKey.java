package ai.khukuri.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    public enum KeyType {
        /** Authenticates telemetry shipped by a tenant application (OTLP path). */
        INGEST,
        /** Authenticates a tenant application calling the LLM gateway. */
        GATEWAY
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, length = 16)
    private KeyType keyType;

    /** First characters of the issued key, for display/identification. Never the secret. */
    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    /** SHA-256 hex of the full key. The raw key is returned exactly once at issuance. */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(length = 255)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID tenantId, KeyType keyType, String keyPrefix, String keyHash, String label) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.keyType = keyType;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.label = label;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public KeyType getKeyType() {
        return keyType;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
