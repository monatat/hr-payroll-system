package com.hrpayroll.backend.auth;

import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRepository;
import com.hrpayroll.backend.user.UserRole;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo-appropriate bootstrap mechanism for the initial HR_ADMIN account --
 * NOT a production secret-management system. Idempotent via
 * {@link UserRepository#existsByEmail(String)}: if the configured email
 * already belongs to a non-HR_ADMIN account, bootstrap fails loudly rather
 * than promoting or overwriting that account.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapAdminProperties properties;

    public BootstrapAdminRunner(
            UserRepository userRepository, PasswordEncoder passwordEncoder, BootstrapAdminProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.getEmail();
        String password = properties.getPassword();

        if (isBlank(email) || isBlank(password)) {
            log.info("Bootstrap admin not configured (BOOTSTRAP_ADMIN_EMAIL/BOOTSTRAP_ADMIN_PASSWORD unset) -- skipping.");
            return;
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getRole() != UserRole.HR_ADMIN) {
                throw new IllegalStateException(
                        "Bootstrap admin email '" + email + "' already exists as a " + user.getRole()
                                + " account. Refusing to promote or modify an existing non-admin account.");
            }
            log.info("Bootstrap admin already exists for '{}' -- skipping creation.", email);
            return;
        }

        User admin = new User(email, passwordEncoder.encode(password), UserRole.HR_ADMIN, AccountStatus.ACTIVE, null);
        userRepository.save(admin);
        log.info("Bootstrap HR_ADMIN account created for '{}'.", email);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
