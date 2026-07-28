package ai.khukuri.incident.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which tenants the current caller may see. This is the tenancy boundary for
 * every read in this service: query methods take the resolved tenant list, so a missing
 * check is a compile-time omission rather than a silent data leak.
 *
 * <ul>
 *   <li>Platform admin ({@code khukuri:owner|admin}) — every tenant.
 *   <li>Service token ({@code SCOPE_internal}, the agent runtime) — every tenant; it acts
 *       on the platform's behalf and is given its tenant by the incident context.
 *   <li>Everyone else — only tenants they hold a role grant for.
 * </ul>
 */
@Component
public class TenantAccess {

    /** Sentinel meaning "no tenant filter" — resolved callers see everything. */
    public static final String ALL = "*";

    public boolean isUnrestricted() {
        return hasAuthority("ROLE_PLATFORM_ADMIN") || hasAuthority("SCOPE_internal");
    }

    /** Tenants this caller may read, or {@link #ALL} for unrestricted callers. */
    public List<String> visibleTenants() {
        if (isUnrestricted()) {
            return List.of(ALL);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return List.of();
        }
        // Authorities look like ROLE_RETAIL-SHOP_ANALYST; recover the tenant slug.
        Set<String> tenants = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_") && a.contains("_"))
                .map(TenantAccess::tenantFromAuthority)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toSet());
        return List.copyOf(tenants);
    }

    /**
     * Resolves a requested tenant against what the caller may see.
     *
     * @param requested the tenant asked for, or null to mean "all I can see"
     * @return the tenant list to filter by, or {@link #ALL} for no filter
     */
    public List<String> resolve(String requested) {
        List<String> visible = visibleTenants();
        if (requested == null || requested.isBlank()) {
            if (visible.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No tenant grants on this token");
            }
            return visible;
        }
        if (isUnrestricted() || visible.contains(requested)) {
            return List.of(requested);
        }
        // Deliberately 403 rather than an empty result: a caller asking for someone
        // else's tenant should be told no, not handed a plausible-looking blank page.
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not authorized for tenant: " + requested);
    }

    private static String tenantFromAuthority(String authority) {
        String withoutPrefix = authority.substring("ROLE_".length());
        int lastUnderscore = withoutPrefix.lastIndexOf('_');
        if (lastUnderscore <= 0) {
            return null;
        }
        String tenant = withoutPrefix.substring(0, lastUnderscore).toLowerCase();
        return "platform".equals(tenant) ? null : tenant;
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
