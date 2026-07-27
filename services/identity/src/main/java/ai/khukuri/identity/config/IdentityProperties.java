package ai.khukuri.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(
        String issuer,
        String adminPassword,
        List<String> desktopRedirectUris,
        Clients clients
) {
    public record Clients(String gatewaySecret) {
    }
}
