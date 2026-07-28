package ai.khukuri.incident.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenancy boundary. These assertions are the difference between multi-tenancy and a
 * data leak, so they are tested directly rather than only through controllers.
 */
class TenantAccessTest {

    private final TenantAccess access = new TenantAccess();

    private void authenticatedWith(String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken("user", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformAdminSeesEverything() {
        authenticatedWith("ROLE_PLATFORM_ADMIN", "ROLE_KHUKURI_OWNER");

        assertThat(access.isUnrestricted()).isTrue();
        assertThat(access.resolve(null)).containsExactly(TenantAccess.ALL);
        assertThat(access.resolve("ember")).containsExactly("ember");
    }

    @Test
    void serviceTokenFromTheAgentRuntimeIsUnrestricted() {
        authenticatedWith("SCOPE_internal");

        assertThat(access.isUnrestricted()).isTrue();
        assertThat(access.resolve("retail-shop")).containsExactly("retail-shop");
    }

    @Test
    void tenantUserSeesOnlyItsOwnTenant() {
        authenticatedWith("ROLE_RETAIL-SHOP_ANALYST");

        assertThat(access.isUnrestricted()).isFalse();
        assertThat(access.visibleTenants()).containsExactly("retail-shop");
        assertThat(access.resolve(null)).containsExactly("retail-shop");
        assertThat(access.resolve("retail-shop")).containsExactly("retail-shop");
    }

    @Test
    void askingForSomeoneElsesTenantIsForbiddenNotEmpty() {
        authenticatedWith("ROLE_RETAIL-SHOP_ANALYST");

        // An empty list would look like "nothing happened there", which is worse than a 403.
        assertThatThrownBy(() -> access.resolve("ember"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not authorized for tenant");
    }

    @Test
    void multipleGrantsYieldMultipleTenants() {
        authenticatedWith("ROLE_RETAIL-SHOP_ANALYST", "ROLE_EMBER_VIEWER");

        List<String> visible = access.visibleTenants();
        assertThat(visible).containsExactlyInAnyOrder("retail-shop", "ember");
    }

    @Test
    void aTokenWithNoGrantsGetsNothing() {
        authenticatedWith("SCOPE_openid");

        assertThat(access.visibleTenants()).isEmpty();
        assertThatThrownBy(() -> access.resolve(null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No tenant grants");
    }
}
