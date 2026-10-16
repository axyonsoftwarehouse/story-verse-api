package com.storyverse.api.ai;

import java.util.List;

public record AiReplyRequest(String system, List<AiMessage> messages, Integer maxTokens) {
}
