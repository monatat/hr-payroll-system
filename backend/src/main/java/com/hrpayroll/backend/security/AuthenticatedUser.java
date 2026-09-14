package com.hrpayroll.backend.security;

/**
 * The authenticated principal resolved from a validated JWT's claims.
 * Deliberately carries only what the token itself carries (id, role,
 * employeeId) -- no per-request database lookup is performed.
 */
public record AuthenticatedUser(Long userId, String role, Long employeeId) {
}
