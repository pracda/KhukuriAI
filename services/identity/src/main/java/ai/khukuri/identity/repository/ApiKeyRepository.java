package ai.khukuri.identity.repository;

import ai.khukuri.identity.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
