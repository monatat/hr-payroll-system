package com.hrpayroll.backend.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the opt-in local demo dataset at startup. Exists only when BOTH the {@code demo} profile is
 * active AND {@code app.demo.enabled=true} ({@code DEMO_DATA_ENABLED=true}) -- never under the default,
 * {@code test}, or any other profile, and never from the flag or the profile alone.
 *
 * <p>Runs on {@link ApplicationReadyEvent}, which Spring Boot publishes only after every
 * {@code ApplicationRunner} has completed, so it always runs after {@code BootstrapAdminRunner} (which
 * has no explicit order). A failure here still aborts startup. Only fixed, non-sensitive messages are
 * logged or thrown -- never a password, hash, or token.
 */
@Component
@Profile("demo")
@ConditionalOnProperty(prefix = "app.demo", name = "enabled", havingValue = "true")
public class DemoDataRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    static final String INVALID_CONFIGURATION_MESSAGE = "Demo data is enabled, but DEMO_ADMIN_PASSWORD and "
            + "DEMO_EMPLOYEE_PASSWORD must both be set to at least " + MIN_PASSWORD_LENGTH + " characters.";

    private static final Logger log = LoggerFactory.getLogger(DemoDataRunner.class);

    private final DemoDataProperties properties;
    private final DemoDataSeeder seeder;

    public DemoDataRunner(DemoDataProperties properties, DemoDataSeeder seeder) {
        this.properties = properties;
        this.seeder = seeder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String adminPassword = properties.getAdminPassword();
        String employeePassword = properties.getEmployeePassword();
        if (!isAcceptable(adminPassword) || !isAcceptable(employeePassword)) {
            throw new IllegalStateException(INVALID_CONFIGURATION_MESSAGE);
        }

        log.info("Demo data profile active -- checking the local demo dataset.");
        DemoDataSeeder.SeedOutcome outcome = seeder.seed(adminPassword, employeePassword);
        if (outcome == DemoDataSeeder.SeedOutcome.CREATED) {
            log.info("Demo dataset created (local demonstration data only).");
        } else {
            log.info("Demo dataset already present -- nothing created or changed.");
        }
    }

    private static boolean isAcceptable(String password) {
        return password != null && !password.isBlank() && password.length() >= MIN_PASSWORD_LENGTH;
    }
}
