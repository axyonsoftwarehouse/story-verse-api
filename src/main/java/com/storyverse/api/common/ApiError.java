package com.storyverse.api.common;

public record ApiError(String error, String code) {

	public ApiError(String error) {
		this(error, null);
	}
}
