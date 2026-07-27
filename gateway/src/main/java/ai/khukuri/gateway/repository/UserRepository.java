package ai.khukuri.gateway.repository;

import ai.khukuri.gateway.model.entity.GatewayUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<GatewayUser, String> {
    Optional<GatewayUser> findByUsername(String username);
    boolean existsByUsername(String username);
    List<GatewayUser> findByOrganizationId(String organizationId);
}
