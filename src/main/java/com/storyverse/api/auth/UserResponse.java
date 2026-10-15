package com.storyverse.api.auth;

public record UserResponse(String id, String email, String name) {

	public static UserResponse from(User user) {
		return new UserResponse(user.id().toString(), user.email(), user.name());
	}
}
