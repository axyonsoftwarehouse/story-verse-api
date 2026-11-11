package com.storyverse.api.auth;

/**
 * Campos ausentes ({@code null}) em {@code avatar}, {@code photo} e {@code cover} apagam o valor;
 * {@code bio} ausente mantém a atual (apps antigos não a mandam) e vazia apaga.
 */
public record ProfileUpdateRequest(String name, String avatar, String photo, String cover, String bio) {
}
