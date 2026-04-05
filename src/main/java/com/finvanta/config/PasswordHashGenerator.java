package com.finvanta.config;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dev-only: Resets all seed user passwords at startup to ensure they
 * match the configured PasswordEncoder. This guarantees login works
 * regardless of the BCrypt hash in data.sql.
 *
 * Default password for all users: password
 *
 * Disable or remove in production.
 */
@Component
public class PasswordHashGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordHashGenerator.class);
    private static final String DEFAULT_PASSWORD = "password";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordHashGenerator(AppUserRepository userRepository,
                                  PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        String hash = passwordEncoder.encode(DEFAULT_PASSWORD);
        List<AppUser> users = userRepository.findAll();
        for (AppUser user : users) {
            user.setPasswordHash(hash);
            userRepository.save(user);
        }
        log.info("=== DEV SEED: Reset {} user passwords to '{}' ===", users.size(), DEFAULT_PASSWORD);
    }
}
