CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    reference VARCHAR(32) NOT NULL UNIQUE,
    tenant_id VARCHAR(63) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    rule_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    observed_value DOUBLE PRECISION NOT NULL,
    threshold_value DOUBLE PRECISION NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    acknowledged_by VARCHAR(255)
);

CREATE INDEX idx_incidents_tenant_status ON incidents(tenant_id, status);

-- Enforces the dedup rule at the database, not just in application logic: at most one
-- unresolved incident per tenant + service + rule, however many detector passes race.
CREATE UNIQUE INDEX idx_incidents_active_rule
    ON incidents(tenant_id, service_name, rule_key)
    WHERE status <> 'RESOLVED';

CREATE TABLE deployments (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(63) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    version VARCHAR(128) NOT NULL,
    deployed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deployed_by VARCHAR(255),
    notes VARCHAR(1000)
);

CREATE INDEX idx_deployments_tenant_time ON deployments(tenant_id, deployed_at DESC);
