package com.hrpayroll.backend.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrpayroll.backend.user.AccountStatus;
import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** Pure unit tests -- no Spring context needed. */
class JwtServiceTest {

    private static final String VALID_SECRET = "unit-test-jwt-signing-secret-value-long-enough-for-hs256";

    @Test
    void generatesTokenWithApprovedClaimsForEmployee() throws Exception {
        JwtProperties properties = properties(VALID_SECRET, 30, "hr-payroll-backend");
        JwtService jwtService = new JwtService(properties);
        User employee = newUser(42L, UserRole.EMPLOYEE, 7L);

        String token = jwtService.generateAccessToken(employee);
        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role", String.class)).isEqualTo("EMPLOYEE");
        assertThat(claims.get("employeeId", Long.class)).isEqualTo(7L);
        assertThat(claims.getIssuer()).isEqualTo("hr-payroll-backend");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());

        // No PII/sensitive claims of any kind.
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "employeeId", "iss", "iat", "exp");
    }

    @Test
    void hrAdminTokenHasNoEmployeeIdClaim() {
        JwtService jwtService = new JwtService(properties(VALID_SECRET, 30, "hr-payroll-backend"));
        User admin = newUser(1L, UserRole.HR_ADMIN, null);

        Claims claims = jwtService.parseClaims(jwtService.generateAccessToken(admin));

        assertThat(claims.get("employeeId")).isNull();
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "iss", "iat", "exp");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwtService = new JwtService(properties(VALID_SECRET, 30, "hr-payroll-backend"));
        Instant past = Instant.now().minusSeconds(3600);
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject("1")
                .claim("role", "HR_ADMIN")
                .issuer("hr-payroll-backend")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwtService.parseClaims(expiredToken))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwtService = new JwtService(properties(VALID_SECRET, 30, "hr-payroll-backend"));
        String token = jwtService.generateAccessToken(newUser(1L, UserRole.HR_ADMIN, null));
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtService.parseClaims(tampered)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void missingSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> new JwtService(properties(null, 30, "hr-payroll-backend")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void tooShortSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> new JwtService(properties("too-short", 30, "hr-payroll-backend")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JwtProperties properties(String secret, long expirationMinutes, String issuer) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpirationMinutes(expirationMinutes);
        properties.setIssuer(issuer);
        return properties;
    }

    private static User newUser(Long id, UserRole role, Long employeeId) {
        return new User(id, "fixture@test.local", "$2a$10$hash", role, AccountStatus.ACTIVE, employeeId);
    }
}
