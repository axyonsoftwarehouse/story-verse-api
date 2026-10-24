package com.storyverse.api.auth;

import com.storyverse.api.admin.SanctionNotice;

public record AuthResponse(String accessToken, String refreshToken, long expiresIn, UserResponse user,
		SanctionNotice activeWarning) {
}
