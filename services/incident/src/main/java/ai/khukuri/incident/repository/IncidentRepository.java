package ai.khukuri.incident.repository;

import ai.khukuri.incident.domain.Incident;
import ai.khukuri.incident.domain.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByReference(String reference);

    /** The dedup lookup: one active incident per tenant + service + rule. */
    @Query("""
            SELECT i FROM Incident i
            WHERE i.tenantId = :tenantId AND i.serviceName = :serviceName
              AND i.ruleKey = :ruleKey AND i.status <> ai.khukuri.incident.domain.IncidentStatus.RESOLVED
            """)
    Optional<Incident> findActive(String tenantId, String serviceName, String ruleKey);

    List<Incident> findByTenantIdInOrderByOpenedAtDesc(List<String> tenantIds);

    List<Incident> findByTenantIdInAndStatusOrderByOpenedAtDesc(List<String> tenantIds,
                                                               IncidentStatus status);

    @Query("SELECT COUNT(i) FROM Incident i")
    long countAll();
}
