package com.storyverse.api.auth;

/**
 * Foto, capa e avatar ausentes ({@code null}) ficam; string vazia apaga.
 * {@code bio} ausente mantém a atual (apps antigos não a mandam) e vazia apaga.
 * {@code profileTheme} ausente mantém o tema; vazio ou {@code dourado} volta ao padrão.
 */
public record ProfileUpdateRequest(String name, String avatar, String photo, String cover, String bio, String profileTheme) {
}
