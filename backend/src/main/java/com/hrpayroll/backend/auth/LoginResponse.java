package com.hrpayroll.backend.auth;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
