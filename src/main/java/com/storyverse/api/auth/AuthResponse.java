package com.storyverse.api.auth;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserResponse user) {
}
