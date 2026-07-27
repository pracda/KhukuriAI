package ai.khukuri.identity.web;

import ai.khukuri.identity.domain.ApiKey;
import ai.khukuri.identity.service.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    public record IssueKeyRequest(@NotNull ApiKey.KeyType type, String label) {
    }

    /** The only response that ever contains the raw key. */
    public record IssuedKeyResponse(UUID id, String key, String prefix, ApiKey.KeyType type, String tenant) {
    }

    public record KeySummaryResponse(UUID id, String prefix, ApiKey.KeyType type, String label,
                                     Instant createdAt, boolean active) {
        static KeySummaryResponse from(ApiKey key) {
            return new KeySummaryResponse(key.getId(), key.getKeyPrefix() + "…", key.getKeyType(),
                    key.getLabel(), key.getCreatedAt(), key.isActive());
        }
    }

    public record VerifyKeyRequest(@NotBlank String key) {
    }

    public record VerifyKeyResponse(boolean valid, String tenant, ApiKey.KeyType type) {
    }

    @PostMapping("/tenants/{slug}/keys")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyResponse issue(@PathVariable String slug, @Valid @RequestBody IssueKeyRequest request) {
        var issued = apiKeyService.issue(slug, request.type(), request.label());
        return new IssuedKeyResponse(issued.id(), issued.key(), issued.prefix(), issued.type(), issued.tenantSlug());
    }

    @GetMapping("/tenants/{slug}/keys")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<KeySummaryResponse> list(@PathVariable String slug) {
        return apiKeyService.listForTenant(slug).stream().map(KeySummaryResponse::from).toList();
    }

    @DeleteMapping("/keys/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        apiKeyService.revoke(id);
    }

    /**
     * Called by platform services (gateway, ingest) with a client-credentials token to
     * validate a key presented by a tenant application. Never exposed to end users.
     */
    @PostMapping("/keys/verify")
    @PreAuthorize("hasAuthority('SCOPE_internal')")
    public ResponseEntity<VerifyKeyResponse> verify(@Valid @RequestBody VerifyKeyRequest request) {
        return apiKeyService.verify(request.key())
                .map(v -> ResponseEntity.ok(new VerifyKeyResponse(true, v.tenantSlug(), v.type())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new VerifyKeyResponse(false, null, null)));
    }
}
