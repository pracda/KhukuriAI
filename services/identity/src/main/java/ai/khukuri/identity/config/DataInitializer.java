package ai.khukuri.identity.config;

import ai.khukuri.identity.domain.Role;
import ai.khukuri.identity.domain.Tenant;
import ai.khukuri.identity.domain.User;
import ai.khukuri.identity.repository.TenantRepository;
import ai.khukuri.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Seeds the three platform tenants and a bootstrap admin on first boot. */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final List<String[]> SEED_TENANTS = List.of(
            new String[]{"khukuri", "Khukuri (platform)"},
            new String[]{"retail-shop", "Retail Shop Management"},
            new String[]{"ember", "Ember POS"});

    @Bean
    public ApplicationRunner seedData(TenantRepository tenants,
                                      UserRepository users,
                                      PasswordEncoder encoder,
                                      IdentityProperties props) {
        return args -> seed(tenants, users, encoder, props);
    }

    @Transactional
    void seed(TenantRepository tenants, UserRepository users,
              PasswordEncoder encoder, IdentityProperties props) {
        for (String[] seed : SEED_TENANTS) {
            if (!tenants.existsBySlug(seed[0])) {
                tenants.save(new Tenant(seed[0], seed[1]));
                log.info("Seeded tenant '{}'", seed[0]);
            }
        }
        if (users.count() == 0) {
            User admin = new User("admin", encoder.encode(props.adminPassword()));
            admin.grantRole("khukuri", Role.OWNER);
            users.save(admin);
            if ("admin123".equals(props.adminPassword())) {
                log.warn("Bootstrap admin created with the DEFAULT password — set IDENTITY_ADMIN_PASSWORD before exposing this service");
            } else {
                log.info("Bootstrap admin user created");
            }
        }
    }
}
