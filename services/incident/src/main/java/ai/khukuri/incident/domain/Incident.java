package ai.khukuri.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    private UUID id;

    /** Human-facing reference, e.g. INC-42. */
    @Column(name = "reference", nullable = false, unique = true, length = 32)
    private String reference;

    @Column(name = "tenant_id", nullable = false, length = 63)
    private String tenantId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    /** Which detection rule fired — also the dedup key while an incident is open. */
    @Column(name = "rule_key", nullable = false, length = 64)
    private String ruleKey;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(name = "observed_value", nullable = false)
    private double observedValue;

    @Column(name = "threshold_value", nullable = false)
    private double thresholdValue;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    protected Incident() {
    }

    public Incident(String reference, String tenantId, String serviceName, String ruleKey,
                    String title, String summary, Severity severity,
                    double observedValue, double thresholdValue) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.tenantId = tenantId;
        this.serviceName = serviceName;
        this.ruleKey = ruleKey;
        this.title = title;
        this.summary = summary;
        this.severity = severity;
        this.observedValue = observedValue;
        this.thresholdValue = thresholdValue;
        this.status = IncidentStatus.OPEN;
        this.openedAt = Instant.now();
        this.updatedAt = this.openedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public double getObservedValue() {
        return observedValue;
    }

    public double getThresholdValue() {
        return thresholdValue;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    /**
     * Lifecycle transitions. Illegal moves throw rather than silently no-op, so a buggy
     * caller surfaces immediately instead of corrupting an incident timeline.
     */
    public void acknowledge(String username) {
        requireOpen("acknowledge");
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedBy = username;
        this.updatedAt = Instant.now();
    }

    public void mitigate() {
        if (status == IncidentStatus.RESOLVED) {
            throw new IllegalStateException("Cannot mitigate a resolved incident");
        }
        this.status = IncidentStatus.MITIGATED;
        this.updatedAt = Instant.now();
    }

    public void resolve() {
        if (status == IncidentStatus.RESOLVED) {
            return; // idempotent: auto-resolution can race a manual resolve
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    /** A still-firing rule refreshes the observed value without reopening or duplicating. */
    public void refreshObservation(double newValue) {
        this.observedValue = newValue;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status != IncidentStatus.RESOLVED;
    }

    private void requireOpen(String action) {
        if (status != IncidentStatus.OPEN) {
            throw new IllegalStateException("Cannot " + action + " an incident that is " + status);
        }
    }
}
