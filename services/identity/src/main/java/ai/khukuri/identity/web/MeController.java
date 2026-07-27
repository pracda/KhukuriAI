package ai.khukuri.identity.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MeController {

    public record MeResponse(String username, List<String> roles) {
    }

    @GetMapping("/api/v1/users/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new MeResponse(jwt.getSubject(), roles == null ? List.of() : roles);
    }
}
