package com.storyverse.api.auth;

public record SignupRequest(String email, String password, String name) {
}
