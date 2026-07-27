package ai.khukuri.identity;

import ai.khukuri.identity.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class IdentityApplicationTests {

    @Autowired
    private TenantRepository tenants;

    @Test
    void contextLoadsAndSeedsTenants() {
        assertThat(tenants.existsBySlug("khukuri")).isTrue();
        assertThat(tenants.existsBySlug("retail-shop")).isTrue();
        assertThat(tenants.existsBySlug("ember")).isTrue();
    }
}
