package com.storyverse.api.library;

/** Abandonar: motivo obrigatório (`slow`, `not_for_me`, `difficult`, `other`) e nota curta opcional. */
public record AbandonRequest(String dnfReason, String dnfNote) {
}
