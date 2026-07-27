package ai.khukuri.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

@Embeddable
public class RoleGrant {

    @Column(name = "tenant_slug", nullable = false, length = 63)
    private String tenantSlug;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    protected RoleGrant() {
    }

    public RoleGrant(String tenantSlug, String role) {
        this.tenantSlug = tenantSlug;
        this.role = role;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public String getRole() {
        return role;
    }

    /** Serialized form used in JWT "roles" claims, e.g. "retail-shop:admin". */
    public String asClaim() {
        return tenantSlug + ":" + role;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoleGrant other
                && tenantSlug.equals(other.tenantSlug)
                && role.equals(other.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantSlug, role);
    }
}
