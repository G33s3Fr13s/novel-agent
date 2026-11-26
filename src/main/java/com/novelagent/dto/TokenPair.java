package com.novelagent.dto;

/**
 * Internal token pair returned by AuthService to AuthController.
 * The refresh token goes into an httpOnly cookie; the AuthResponse goes into the JSON body.
 */
public record TokenPair(AuthResponse authResponse, String rawRefreshToken) {

    public static TokenPair of(AuthResponse authResponse, String rawRefreshToken) {
        return new TokenPair(authResponse, rawRefreshToken);
    }
}
