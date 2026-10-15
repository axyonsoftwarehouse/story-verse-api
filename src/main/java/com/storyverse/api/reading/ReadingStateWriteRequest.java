package com.storyverse.api.reading;

import tools.jackson.databind.JsonNode;

/** `baseUpdatedAt` é o updatedAt lido no GET (null na primeira gravação da conta). */
public record ReadingStateWriteRequest(JsonNode data, String baseUpdatedAt) {
}
