package com.storyverse.api.auth;

public record ResetPasswordRequest(String token, String password) {
}
