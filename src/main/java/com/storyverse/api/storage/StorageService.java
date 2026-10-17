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
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;

/**
 * Armazenamento em disco local na VPS (Marco 2). As regras de acesso ficam aqui (não no storage):
 * cada pessoa só escreve/lê a própria pasta; `profile-media` tem leitura pública.
 */
@Service
public class StorageService {

	private final Path baseDir;
	private final StorageAccess access;
	private final long quotaBytes;
	private final int quotaObjects;

	public StorageService(@Value("${app.storage.dir:/data/media}") String directory, StorageAccess access,
			@Value("${app.storage.quota-bytes:268435456}") long quotaBytes,
			@Value("${app.storage.quota-objects:1000}") int quotaObjects) {
		this.baseDir = Path.of(directory).toAbsolutePath().normalize();
		this.access = access;
		this.quotaBytes = quotaBytes;
		this.quotaObjects = quotaObjects;
	}

	@Transactional
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
		access.lock("storage-capacity");
		access.lock("storage-owner:" + userId);
		access.setUser(userId);
		if (bucket == StorageBucket.COMMUNITY_BOOKS) access.lockCommunity(own);
		access.assertWritable(bucket, own);
		try {
			Files.createDirectories(baseDir);
			if (Files.getFileStore(baseDir).getUsableSpace() - bytes.length < 2L * 1024 * 1024 * 1024)
				throw new AuthException(507, "Armazenamento temporariamente indisponível.");
			long total = 0;
			long count = 0;
			for (StorageBucket item : StorageBucket.values()) {
				Path folder = baseDir.resolve(item.id()).resolve(userId.toString());
				if (!Files.exists(folder)) continue;
				try (var files = Files.walk(folder)) {
					for (Path file : files.filter(Files::isRegularFile).toList()) {
						total += Files.size(file);
						count++;
					}
				}
			}
			boolean existing = Files.isRegularFile(target);
			if (total - (existing ? Files.size(target) : 0) + bytes.length > quotaBytes
					|| count + (existing ? 0 : 1) > quotaObjects)
				throw new AuthException(413, "Limite de armazenamento da conta atingido.");
			Files.createDirectories(target.getParent());
			Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			try {
				Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
				Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} finally { Files.deleteIfExists(temporary); }
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao gravar o arquivo", exception);
		}
	}

	@Transactional(readOnly = true)
	public Optional<byte[]> readFor(UUID userId, StorageBucket bucket, String path) {
		String cleaned = safePath(path);
		if (!access.readable(userId, bucket, cleaned)) throw new AuthException(403, "Sem permissão para este arquivo.");
		return read(bucket, cleaned);
	}

	public boolean exists(StorageBucket bucket, String path) { return Files.isRegularFile(resolve(bucket, safePath(path))); }

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

	@Transactional
	public void delete(UUID userId, StorageBucket bucket, String path) {
		String cleaned = safePath(path);
		access.lock("storage-owner:" + cleaned.split("/", 2)[0]);
		if (bucket == StorageBucket.COMMUNITY_BOOKS) access.lockCommunity(cleaned);
		if (!access.deletable(userId, bucket, cleaned)) throw new AuthException(403, "Sem permissão para apagar este arquivo.");
		Path target = resolve(bucket, cleaned);
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
		if (cleaned.length() > 300 || !cleaned.matches("[a-zA-Z0-9_-]+(/[a-zA-Z0-9_.-]+)+")
				|| cleaned.contains("..")) {
			throw new AuthException(400, "Caminho inválido.");
		}
		return cleaned;
	}

	private Path resolve(StorageBucket bucket, String relative) {
		Path target = baseDir.resolve(bucket.id()).resolve(relative).normalize();
		if (!target.startsWith(baseDir.resolve(bucket.id()))) {
			throw new AuthException(400, "Caminho inválido.");
		}
		for (Path part = target; part != null && part.startsWith(baseDir); part = part.getParent())
			if (Files.isSymbolicLink(part)) throw new AuthException(400, "Caminho inválido.");
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
