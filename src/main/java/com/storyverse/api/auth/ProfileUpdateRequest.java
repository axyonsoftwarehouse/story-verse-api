package com.storyverse.api.auth;

public record ProfileUpdateRequest(String name, String avatar, String photo, String cover) {
}
