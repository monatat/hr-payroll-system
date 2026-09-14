package com.hrpayroll.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRepository;
import com.hrpayroll.backend.user.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Pure unit tests against mocks -- no Spring context, no real database writes. */
@ExtendWith(MockitoExtension.class)
class BootstrapAdminRunnerTest {

    private static final String EMAIL = "bootstrap.admin@test.local";
    private static final String PASSWORD = "BootstrapPass123!";

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    private BootstrapAdminProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BootstrapAdminProperties();
    }

    @Test
    void skipsWhenNotConfigured() {
        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, passwordEncoder, properties);

        runner.run(null);

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createsActiveHrAdminWithNoEmployeeWhenEmailDoesNotExist() {
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, passwordEncoder, properties);
        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();

        assertThat(created.getEmail()).isEqualTo(EMAIL);
        assertThat(created.getRole()).isEqualTo(UserRole.HR_ADMIN);
        assertThat(created.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getEmployeeId()).isNull();
        assertThat(created.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
    }

    @Test
    void isIdempotentWhenAdminAlreadyExists() {
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);
        User existingAdmin = new User(EMAIL, passwordEncoder.encode(PASSWORD), UserRole.HR_ADMIN, AccountStatus.ACTIVE, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingAdmin));

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, passwordEncoder, properties);
        runner.run(null);
        runner.run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNotPromoteOrModifyExistingNonAdminAccount() {
        properties.setEmail(EMAIL);
        properties.setPassword(PASSWORD);
        User existingEmployee = new User(EMAIL, "some-other-hash", UserRole.EMPLOYEE, AccountStatus.ACTIVE, 5L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingEmployee));

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, passwordEncoder, properties);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
        verify(userRepository, times(1)).findByEmail(EMAIL);
    }
}
