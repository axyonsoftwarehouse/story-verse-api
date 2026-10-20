package com.storyverse.api.ai;

/** Quota diária de mensagens de IA. `remaining` ja considera a mensagem reservada. */
public record AiQuota(int limit, int used, int remaining, String day, String resetsAt) {
}
