package com.storyverse.api.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.storyverse.api.auth.AuthException;

/**
 * Armazenamento em disco local na VPS (Marco 2). As regras de acesso ficam aqui (não no storage):
 * cada pessoa só escreve/lê a própria pasta; `profile-media` tem leitura pública.
 */
@Service
public class StorageService {

	private final Path baseDir;

	public StorageService(@Value("${app.storage.dir:/data/media}") String directory) {
		this.baseDir = Path.of(directory).toAbsolutePath().normalize();
	}

	public void write(UUID userId, StorageBucket bucket, String path, String contentType, byte[] bytes) {
		String own = requireOwn(userId, path);
		String type = normalizeType(contentType, own);
		if (!bucket.allowedTypes().contains(type)) {
			throw new AuthException(415, "Formato de arquivo não aceito.");
		}
		if (bytes.length == 0) {
			throw new AuthException(400, "Arquivo vazio.");
		}
		if (bytes.length > bucket.maxBytes()) {
			throw new AuthException(413, "Arquivo grande demais.");
		}
		Path target = resolve(bucket, own);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao gravar o arquivo", exception);
		}
	}

	/** Leitura de um arquivo (público ou autenticado). */
	public Optional<byte[]> read(StorageBucket bucket, String path) {
		Path target = resolve(bucket, safePath(path));
		if (!Files.isRegularFile(target)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(target));
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao ler o arquivo", exception);
		}
	}

	public void delete(UUID userId, StorageBucket bucket, String path) {
		Path target = resolve(bucket, requireOwn(userId, path));
		try {
			Files.deleteIfExists(target);
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao apagar o arquivo", exception);
		}
	}

	/** Qualquer arquivo de um bucket privado tem de estar na pasta da própria pessoa. */
	public static String requireOwn(UUID userId, String path) {
		String cleaned = safePath(path);
		if (!cleaned.startsWith(userId + "/")) {
			throw new AuthException(403, "Sem permissão para este arquivo.");
		}
		return cleaned;
	}

	private static String safePath(String path) {
		if (path == null || path.isBlank()) {
			throw new AuthException(400, "Caminho inválido.");
		}
		String cleaned = path.replace('\\', '/');
		if (cleaned.startsWith("/") || cleaned.contains("..") || cleaned.indexOf('\0') >= 0) {
			throw new AuthException(400, "Caminho inválido.");
		}
		return cleaned;
	}

	private Path resolve(StorageBucket bucket, String relative) {
		Path target = baseDir.resolve(bucket.id()).resolve(relative).normalize();
		if (!target.startsWith(baseDir)) {
			throw new AuthException(400, "Caminho inválido.");
		}
		return target;
	}

	private static String normalizeType(String contentType, String path) {
		String type = contentType;
		if (type == null || type.isBlank()) {
			type = guessFromPath(path);
		}
		int semicolon = type.indexOf(';');
		return (semicolon >= 0 ? type.substring(0, semicolon) : type).trim().toLowerCase();
	}

	private static String guessFromPath(String path) {
		String lower = path.toLowerCase();
		if (lower.endsWith(".webp")) return "image/webp";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".gz")) return "application/gzip";
		if (lower.endsWith(".txt")) return "text/plain";
		return "application/octet-stream";
	}
}
