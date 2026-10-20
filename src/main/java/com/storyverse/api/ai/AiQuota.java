package com.storyverse.api.ai;

/** Quota diária de mensagens de IA. `remaining` já considera a mensagem reservada. */
public record AiQuota(int limit, int used, int remaining, int credits, String day, String resetsAt) {
}
