package com.hrpayroll.backend.auth;

import com.hrpayroll.backend.audit.AuditService;
import com.hrpayroll.backend.security.jwt.JwtService;
import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authenticates users directly against {@link UserRepository} rather than
 * through Spring Security's {@code AuthenticationManager} -- there is only
 * one place that needs to know how to authenticate, and doing it directly
 * keeps the "identical response for every failure reason" guarantee in a
 * single, easy-to-audit code path.
 */
@Service
public class AuthService {

    private static final String LOGIN_FAILED = "LOGIN_FAILED";
    private static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    private static final String ENTITY_USER = "USER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    public LoginResponse login(String email, String rawPassword) {
        Optional<User> maybeUser = userRepository.findByEmail(email);

        if (maybeUser.isEmpty()) {
            auditService.record(null, LOGIN_FAILED, ENTITY_USER, null,
                    Map.of("email", email, "reason", "EMAIL_NOT_FOUND"));
            throw new AuthenticationFailedException();
        }

        User user = maybeUser.get();

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            auditService.record(user.getId(), LOGIN_FAILED, ENTITY_USER, user.getId(),
                    Map.of("email", email, "reason", "ACCOUNT_DISABLED"));
            throw new AuthenticationFailedException();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            auditService.record(user.getId(), LOGIN_FAILED, ENTITY_USER, user.getId(),
                    Map.of("email", email, "reason", "INVALID_PASSWORD"));
            throw new AuthenticationFailedException();
        }

        String token = jwtService.generateAccessToken(user);
        auditService.record(user.getId(), LOGIN_SUCCESS, ENTITY_USER, user.getId(), Map.of("email", email));

        return new LoginResponse(token, "Bearer", jwtService.getExpirationSeconds());
    }
}
