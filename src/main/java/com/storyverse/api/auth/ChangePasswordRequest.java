package com.storyverse.api.auth;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
