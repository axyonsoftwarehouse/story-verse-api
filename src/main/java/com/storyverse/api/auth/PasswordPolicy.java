package com.storyverse.api.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Política de senha sem enviar a senha a terceiros: mínimo de 8 caracteres, limite do BCrypt e
 * blocklist local de senhas comuns/vazadas conhecidas + e-mail/nome da conta.
 */
@Component
public class PasswordPolicy {

	private static final int MIN_LENGTH = 8;
	private static final int MAX_BYTES = 72;
	private final Set<String> common = new HashSet<>();

	public PasswordPolicy() {
		try (InputStream input = PasswordPolicy.class.getResourceAsStream("/common-passwords.txt")) {
			if (input != null) {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(input, StandardCharsets.UTF_8))) {
					reader.lines().map(String::trim).filter(s -> !s.isEmpty())
							.map(s -> s.toLowerCase(Locale.ROOT)).forEach(common::add);
				}
			}
		} catch (IOException ignored) {
			// Sem a lista, as outras regras continuam valendo.
		}
	}

	public void validate(String password, String email, String name) {
		if (password == null || password.length() < MIN_LENGTH) {
			throw new AuthException(400, "A senha precisa de pelo menos 8 caracteres.");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new AuthException(400, "Senha grande demais.");
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (common.contains(lower)) {
			throw new AuthException(400, "Escolha uma senha menos comum e difícil de adivinhar.");
		}
		String comparable = normalize(password);
		String local = email == null ? "" : email.split("@", 2)[0].trim().toLowerCase(Locale.ROOT);
		if (local.length() >= 4 && comparable.contains(normalize(local))) {
			throw new AuthException(400, "A senha não pode conter o seu e-mail.");
		}
		String firstName = name == null ? "" : name.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (firstName.length() >= 4 && comparable.contains(normalize(firstName))) {
			throw new AuthException(400, "A senha não pode conter o seu nome.");
		}
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}
}
