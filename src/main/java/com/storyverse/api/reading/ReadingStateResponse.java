package com.storyverse.api.reading;

import tools.jackson.databind.JsonNode;

public record ReadingStateResponse(JsonNode data, String updatedAt) {
}
