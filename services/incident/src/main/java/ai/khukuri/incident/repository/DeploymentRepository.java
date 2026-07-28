package ai.khukuri.incident.repository;

import ai.khukuri.incident.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByTenantIdInAndDeployedAtAfterOrderByDeployedAtDesc(
            List<String> tenantIds, Instant since);
}
