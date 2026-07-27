package ai.khukuri.identity.service;

import ai.khukuri.identity.domain.RoleGrant;
import ai.khukuri.identity.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DbUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        // Authorities carry the raw "tenant:role" grants; the token customizer copies
        // them into the JWT "roles" claim, and resource servers map them from there.
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(user.getRoles().stream()
                        .map(RoleGrant::asClaim)
                        .map(SimpleGrantedAuthority::new)
                        .toList())
                .build();
    }
}
