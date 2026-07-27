package ai.khukuri.gateway.model.response;

import ai.khukuri.gateway.service.ApiKeyService;

/**
 * Returned exactly once at creation time — {@code apiKey} is the raw secret,
 * never persisted or retrievable again after this response (mirrors how
 * {@link ai.khukuri.gateway.model.entity.ApiKey} only stores its hash).
 * Shared by the admin-issued flow (AdminOrganizationController) and the
 * self-service flow (UserController).
 */
public record CreateApiKeyResponse(ApiKeyResponse key, String apiKey) {
    public static CreateApiKeyResponse from(ApiKeyService.CreatedKey created) {
        return new CreateApiKeyResponse(ApiKeyResponse.from(created.apiKey()), created.rawKey());
    }
}
