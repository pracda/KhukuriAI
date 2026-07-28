package ai.khukuri.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A recorded deployment. Correlating a failure with "what changed just before it" is the
 * single highest-value signal in root-cause analysis, so deployments are first-class
 * rather than inferred.
 *
 * <p>Recorded explicitly by CI/CD via the deployments API. Deriving them from telemetry
 * (a changed {@code service.version} resource attribute) is possible but less reliable —
 * it cannot see a deploy that never emitted telemetry.
 */
@Entity
@Table(name = "deployments")
public class Deployment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 63)
    private String tenantId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(nullable = false, length = 128)
    private String version;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @Column(name = "deployed_by")
    private String deployedBy;

    @Column(length = 1000)
    private String notes;

    protected Deployment() {
    }

    public Deployment(String tenantId, String serviceName, String version,
                      Instant deployedAt, String deployedBy, String notes) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.serviceName = serviceName;
        this.version = version;
        this.deployedAt = deployedAt != null ? deployedAt : Instant.now();
        this.deployedBy = deployedBy;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getVersion() {
        return version;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }

    public String getDeployedBy() {
        return deployedBy;
    }

    public String getNotes() {
        return notes;
    }
}
