package ai.khukuri.identity.web;

import ai.khukuri.identity.domain.Tenant;
import ai.khukuri.identity.repository.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantRepository tenants;

    public TenantController(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public record CreateTenantRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
                    message = "slug must be lowercase alphanumeric with hyphens") String slug,
            @NotBlank String displayName) {
    }

    public record TenantResponse(String slug, String displayName, Instant createdAt) {
        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(tenant.getSlug(), tenant.getDisplayName(), tenant.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<TenantResponse> list() {
        return tenants.findAll().stream().map(TenantResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest request) {
        if (tenants.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("Tenant already exists: " + request.slug());
        }
        return TenantResponse.from(tenants.save(new Tenant(request.slug(), request.displayName())));
    }
}
