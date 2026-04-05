package com.finvanta.config;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.TenantContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public CustomUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String tenantId;
        try {
            tenantId = TenantContext.getCurrentTenant();
        } catch (Exception e) {
            tenantId = "DEFAULT";
        }

        AppUser appUser = userRepository.findByTenantIdAndUsername(tenantId, username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (!appUser.isActive()) {
            throw new UsernameNotFoundException("User account is disabled: " + username);
        }

        if (appUser.isLocked()) {
            throw new UsernameNotFoundException("User account is locked: " + username);
        }

        return new User(
            appUser.getUsername(),
            appUser.getPasswordHash(),
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name()))
        );
    }
}
