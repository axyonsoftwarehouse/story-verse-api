package com.storyverse.api.community;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.storyverse.api.auth.AuthException;

/**
 * Capa do acervo: caminho público do próprio Storage, caminhos locais de capa do web ou HTTPS
 * de hosts conhecidos (Open Library/Internet Archive e o host público da API). Bloqueia
 * javascript:, data:, http:, credenciais embutidas e hosts arbitrários.
 */
@Component
public class CoverUrlPolicy {

	private static final Set<String> LOCAL_PREFIXES = Set.of(
			"/api/v1/storage/public/profile-media/",
			"/ol-covers/",
			"/gutenberg/",
			"/gutenberg-mirror/",
			"/icons/");

	private final Set<String> hosts;

	public CoverUrlPolicy(
			@Value("${app.public-api-url:https://api.storyverse.2.29.42.104.sslip.io}") String publicApiUrl,
			@Value("${app.community.cover-hosts:covers.openlibrary.org,openlibrary.org,www.openlibrary.org,archive.org,www.archive.org}") String[] coverHosts) {
		Set<String> allowed = new LinkedHashSet<>();
		Arrays.stream(coverHosts).map(String::trim).filter(s -> !s.isEmpty())
				.map(s -> s.toLowerCase(Locale.ROOT)).forEach(allowed::add);
		try {
			String host = URI.create(publicApiUrl.trim()).getHost();
			if (host != null && !host.isBlank()) allowed.add(host.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			// Configuração inválida: os hosts padrão continuam valendo.
		}
		this.hosts = Set.copyOf(allowed);
	}

	/** Devolve a capa normalizada ou null; lança 400 quando o valor não é permitido. */
	public String require(String raw) {
		String cover = raw == null ? null : raw.trim();
		if (cover == null || cover.isEmpty()) {
			return null;
		}
		if (cover.length() > 300) {
			throw new AuthException(400, "Capa grande demais.");
		}
		for (String prefix : LOCAL_PREFIXES) {
			if (cover.startsWith(prefix)) return cover;
		}
		try {
			URI uri = URI.create(cover);
			if ("https".equalsIgnoreCase(uri.getScheme())
					&& uri.getHost() != null
					&& uri.getUserInfo() == null
					&& allowed(uri.getHost())) {
				return cover;
			}
		} catch (IllegalArgumentException ignored) {
			// cai no erro abaixo
		}
		throw new AuthException(400, "Capa de origem não permitida.");
	}

	private boolean allowed(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		if (hosts.contains(normalized)) return true;
		for (String allowed : hosts) {
			if (allowed.startsWith("*.") && normalized.endsWith(allowed.substring(1))) return true;
		}
		return false;
	}
}
