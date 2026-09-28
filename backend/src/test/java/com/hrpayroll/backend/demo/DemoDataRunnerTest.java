package com.hrpayroll.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lightweight activation/configuration tests for {@link DemoDataRunner} (Phase 12C-2). No database and
 * no real seeder: the seeder is a mock, so these tests can never seed the shared development database.
 * Activation requires BOTH the {@code demo} profile and {@code app.demo.enabled=true}.
 */
@ExtendWith(OutputCaptureExtension.class)
class DemoDataRunnerTest {

    private static final String ADMIN_PASSWORD = "AdminSecret-7Qx!";
    private static final String EMPLOYEE_PASSWORD = "EmployeeSecret-9Lm!";

    @Configuration
    @EnableConfigurationProperties(DemoDataProperties.class)
    static class MockSeederConfig {
        @Bean
        DemoDataSeeder demoDataSeeder() {
            return mock(DemoDataSeeder.class);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MockSeederConfig.class, DemoDataRunner.class);

    private static ApplicationContextRunner withProfiles(ApplicationContextRunner runner, String... profiles) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    // ---------------------------------------------------------------- activation guards

    @Test
    void noRunnerUnderTheDefaultProfileWithoutTheFlag() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(DemoDataRunner.class));
    }

    @Test
    void noRunnerUnderTheOrdinaryTestProfileEvenWithTheFlag() {
        withProfiles(contextRunner, "test").withPropertyValues("app.demo.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataRunner.class));
    }

    @Test
    void noRunnerUnderTheDemoProfileWithoutTheEnableFlag() {
        withProfiles(contextRunner, "demo")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataRunner.class));
        withProfiles(contextRunner, "demo").withPropertyValues("app.demo.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataRunner.class));
    }

    @Test
    void noRunnerWithTheEnableFlagButWithoutTheDemoProfile() {
        contextRunner.withPropertyValues("app.demo.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataRunner.class));
    }

    @Test
    void runnerExistsOnlyWithBothTheDemoProfileAndTheEnableFlag() {
        withProfiles(contextRunner, "demo").withPropertyValues("app.demo.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DemoDataRunner.class));
    }

    // ---------------------------------------------------------------- password validation

    @Test
    void missingAdminPasswordFailsSafelyWithoutSeeding(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        DemoDataRunner runner = new DemoDataRunner(properties(null, EMPLOYEE_PASSWORD), seeder);

        assertThatThrownBy(runner::onApplicationReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DemoDataRunner.INVALID_CONFIGURATION_MESSAGE)
                .hasMessageNotContaining(EMPLOYEE_PASSWORD);
        verifyNoInteractions(seeder);
        assertThat(output.getAll()).doesNotContain(EMPLOYEE_PASSWORD);
    }

    @Test
    void missingEmployeePasswordFailsSafelyWithoutSeeding(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        DemoDataRunner runner = new DemoDataRunner(properties(ADMIN_PASSWORD, "   "), seeder);

        assertThatThrownBy(runner::onApplicationReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DemoDataRunner.INVALID_CONFIGURATION_MESSAGE)
                .hasMessageNotContaining(ADMIN_PASSWORD);
        verifyNoInteractions(seeder);
        assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD);
    }

    @Test
    void passwordShorterThanTwelveCharactersFailsSafelyWithoutSeeding(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        String elevenChars = "Short-Pw-11";
        DemoDataRunner runner = new DemoDataRunner(properties(ADMIN_PASSWORD, elevenChars), seeder);

        assertThatThrownBy(runner::onApplicationReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DemoDataRunner.INVALID_CONFIGURATION_MESSAGE)
                .hasMessageNotContaining(elevenChars)
                .hasMessageNotContaining(ADMIN_PASSWORD);
        verifyNoInteractions(seeder);
        assertThat(output.getAll()).doesNotContain(elevenChars).doesNotContain(ADMIN_PASSWORD);
    }

    @Test
    void validConfigurationCallsTheSeederExactlyOnceWithoutLoggingPasswords(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        when(seeder.seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD)).thenReturn(DemoDataSeeder.SeedOutcome.CREATED);
        DemoDataRunner runner = new DemoDataRunner(properties(ADMIN_PASSWORD, EMPLOYEE_PASSWORD), seeder);

        runner.onApplicationReady();

        verify(seeder, times(1)).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);
        assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD).doesNotContain(EMPLOYEE_PASSWORD);
    }

    @Test
    void alreadyPresentOutcomeIsLoggedWithoutPasswords(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        when(seeder.seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD)).thenReturn(DemoDataSeeder.SeedOutcome.ALREADY_PRESENT);
        DemoDataRunner runner = new DemoDataRunner(properties(ADMIN_PASSWORD, EMPLOYEE_PASSWORD), seeder);

        runner.onApplicationReady();

        verify(seeder, times(1)).seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);
        assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD).doesNotContain(EMPLOYEE_PASSWORD);
    }

    @Test
    void seederConflictPropagatesItsFixedMessageWithoutPasswords(CapturedOutput output) {
        DemoDataSeeder seeder = mock(DemoDataSeeder.class);
        when(seeder.seed(ADMIN_PASSWORD, EMPLOYEE_PASSWORD)).thenThrow(new DemoDataConflictException());
        DemoDataRunner runner = new DemoDataRunner(properties(ADMIN_PASSWORD, EMPLOYEE_PASSWORD), seeder);

        assertThatThrownBy(runner::onApplicationReady)
                .isInstanceOf(DemoDataConflictException.class)
                .hasMessage(DemoDataConflictException.MESSAGE);
        assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD).doesNotContain(EMPLOYEE_PASSWORD);
    }

    @Test
    void propertiesStringRepresentationNeverExposesPasswords() {
        DemoDataProperties properties = properties(ADMIN_PASSWORD, EMPLOYEE_PASSWORD);

        assertThat(properties.toString()).doesNotContain(ADMIN_PASSWORD).doesNotContain(EMPLOYEE_PASSWORD);
    }

    private static DemoDataProperties properties(String adminPassword, String employeePassword) {
        DemoDataProperties properties = new DemoDataProperties();
        properties.setEnabled(true);
        properties.setAdminPassword(adminPassword);
        properties.setEmployeePassword(employeePassword);
        return properties;
    }
}
