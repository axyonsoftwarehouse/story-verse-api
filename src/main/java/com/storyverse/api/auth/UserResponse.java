package com.storyverse.api.auth;

import java.util.Map;

public record UserResponse(
		String id,
		String email,
		String created_at,
		Map<String, Object> user_metadata) {

	public static UserResponse from(User user) {
		return new UserResponse(
				user.id().toString(),
				user.email(),
				user.createdAt() == null ? null : user.createdAt().toString(),
				user.metadata());
	}
}
