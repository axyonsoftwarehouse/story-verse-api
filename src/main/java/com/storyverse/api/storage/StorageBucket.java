package com.storyverse.api.storage;

import java.util.Set;

import com.storyverse.api.auth.AuthException;

/** Os três "armários" do Storyverse (ver supabase/*.sql). */
public enum StorageBucket {
	/** Fotos de perfil e capas de livro: leitura pública, escrita só na pasta da pessoa. */
	PROFILE_MEDIA("profile-media", true, 2L * 1024 * 1024, Set.of("image/webp", "image/jpeg", "image/png")),
	/** Livros importados (texto comprimido): privado, só o dono lê e grava. */
	USER_BOOKS("user-books", false, 50L * 1024 * 1024, Set.of("application/gzip")),
	/** Texto dos livros da comunidade: privado (autenticado), escrita só na pasta da pessoa. */
	COMMUNITY_BOOKS("community-books", false, 10L * 1024 * 1024, Set.of("text/plain"));

	private final String id;
	private final boolean publiclyReadable;
	private final long maxBytes;
	private final Set<String> allowedTypes;

	StorageBucket(String id, boolean publiclyReadable, long maxBytes, Set<String> allowedTypes) {
		this.id = id;
		this.publiclyReadable = publiclyReadable;
		this.maxBytes = maxBytes;
		this.allowedTypes = allowedTypes;
	}

	public static StorageBucket from(String id) {
		for (StorageBucket bucket : values()) {
			if (bucket.id.equals(id)) {
				return bucket;
			}
		}
		throw new AuthException(404, "Armazenamento não encontrado.");
	}

	public String id() {
		return id;
	}

	public boolean publiclyReadable() {
		return publiclyReadable;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public Set<String> allowedTypes() {
		return allowedTypes;
	}
}
