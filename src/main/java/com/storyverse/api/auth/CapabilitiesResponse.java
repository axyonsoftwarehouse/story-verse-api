package com.storyverse.api.auth;

import java.util.List;
import java.util.Map;

public record CapabilitiesResponse(List<String> roles, List<String> features,
		Map<String, Object> subscription) {
}
