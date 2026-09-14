package com.hrpayroll.backend.security.jwt;

import com.hrpayroll.backend.user.User;
import com.hrpayroll.backend.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and parses stateless HS256 JWT access tokens. There is no refresh
 * token and no server-side revocation in v1 -- a token remains valid until
 * its (short, configurable) expiry even if the account is disabled in the
 * meantime. This is a documented, intentional demo-scope tradeoff.
 */
@Component
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits, required for HS256

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is not configured. Set the JWT_SECRET environment variable "
                            + "(app.jwt.secret) before starting the application. Refusing to start with "
                            + "a missing or default secret.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing secret (JWT_SECRET / app.jwt.secret) is too short for HS256. "
                            + "It must be at least " + MIN_SECRET_BYTES + " bytes long.");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getExpirationMinutes(), ChronoUnit.MINUTES);

        JwtBuilder builder = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole().name())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256);

        if (user.getRole() == UserRole.EMPLOYEE && user.getEmployeeId() != null) {
            builder.claim("employeeId", user.getEmployeeId());
        }

        return builder.compact();
    }

    /**
     * Parses and verifies a token, returning its claims. Throws
     * {@link io.jsonwebtoken.JwtException} (expired, malformed, bad
     * signature, etc.) if the token is not valid.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return properties.getExpirationMinutes() * 60;
    }
}
