package ai.khukuri.identity.service;

import ai.khukuri.identity.domain.ApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ApiKeyServiceTest {

    @Autowired
    private ApiKeyService service;

    @Test
    void issuedKeyVerifiesToItsTenantAndType() {
        var issued = service.issue("retail-shop", ApiKey.KeyType.INGEST, "otel collector");

        assertThat(issued.key()).startsWith("khk_ing_");
        assertThat(issued.prefix()).isEqualTo(issued.key().substring(0, 12));

        var verified = service.verify(issued.key());
        assertThat(verified).isPresent();
        assertThat(verified.get().tenantSlug()).isEqualTo("retail-shop");
        assertThat(verified.get().type()).isEqualTo(ApiKey.KeyType.INGEST);
    }

    @Test
    void revokedKeyNoLongerVerifies() {
        var issued = service.issue("ember", ApiKey.KeyType.GATEWAY, null);
        assertThat(service.verify(issued.key())).isPresent();

        service.revoke(issued.id());
        assertThat(service.verify(issued.key())).isEmpty();
    }

    @Test
    void bogusKeyDoesNotVerify() {
        assertThat(service.verify("khk_gw_not-a-real-key")).isEmpty();
    }

    @Test
    void unknownTenantIsRejected() {
        assertThatThrownBy(() -> service.issue("nope", ApiKey.KeyType.INGEST, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}
