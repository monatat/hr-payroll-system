package com.hrpayroll.backend.auth;

/**
 * Thrown for every login failure reason (nonexistent email, wrong password,
 * disabled account) so a single exception handler can guarantee an
 * identical response across all of them. The reason is never carried on
 * the exception itself -- callers audit the specific reason separately,
 * server-side only.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password.");
    }
}
