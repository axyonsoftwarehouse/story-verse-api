package com.storyverse.api.groups;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.storyverse.api.auth.AuthException;

/**
 * Foto e áudio dos grupos no disco da API, numa pasta só deles (fora do Storage genérico: a rota
 * pública de upload não grava nem lê aqui). O arquivo vive pouco: some depois de {@link #TTL}.
 */
@Component
public class ChatMediaStore {

	private static final Logger log = LoggerFactory.getLogger(ChatMediaStore.class);

	/** Tempo para o grupo baixar; cada celular guarda a própria cópia. */
	static final Duration TTL = Duration.ofHours(24);
	static final long MAX_BYTES = 1_500_000;
	/** Disco livre mínimo para aceitar mídia nova (o resto da API não pode ficar sem espaço). */
	private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;

	static final Map<String, String> IMAGE_TYPES = Map.of("image/webp", "webp", "image/jpeg", "jpeg", "image/png", "png");
	static final Map<String, String> AUDIO_TYPES = Map.of("audio/webm", "webm", "audio/ogg", "ogg", "audio/mp4", "mp4");
	private static final Set<String> KINDS = Set.of("image", "audio");

	private final Path dir;

	public ChatMediaStore(@Value("${app.storage.dir:/data/media}") String storageDir) {
		this.dir = Path.of(storageDir).toAbsolutePath().normalize().resolve("chat-media");
	}

	/** Tipo do arquivo normalizado ("audio/webm;codecs=opus" vira "audio/webm"), ou erro. */
	static String mime(String kind, String contentType) {
		if (!KINDS.contains(kind)) throw new AuthException(400, "Tipo de mídia inválido.");
		String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase();
		Map<String, String> allowed = "image".equals(kind) ? IMAGE_TYPES : AUDIO_TYPES;
		if (!allowed.containsKey(type)) throw new AuthException(415, "Formato de arquivo não aceito.");
		return type;
	}

	/** Grava o arquivo (no máximo {@link #MAX_BYTES}) e devolve o caminho relativo e o tamanho. */
	record Stored(String path, long bytes) {
	}

	Stored write(UUID groupId, String mime, InputStream input) {
		String name = groupId + "/" + UUID.randomUUID();
		Path target = dir.resolve(name).normalize();
		if (!target.startsWith(dir)) throw new AuthException(400, "Caminho inválido.");
		Path temporary = null;
		try {
			Files.createDirectories(target.getParent());
			if (Files.getFileStore(dir).getUsableSpace() < MIN_FREE_BYTES) {
				throw new AuthException(507, "Sem espaço para mídia agora. Tente mais tarde.");
			}
			temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			long written = 0;
			byte[] buffer = new byte[16 * 1024];
			try (InputStream in = input; var out = Files.newOutputStream(temporary)) {
				for (int read; (read = in.read(buffer)) != -1;) {
					written += read;
					if (written > MAX_BYTES) throw new AuthException(413, "Arquivo grande demais.");
					out.write(buffer, 0, read);
				}
			}
			if (written == 0) throw new AuthException(400, "Arquivo vazio.");
			// O tipo é declarado pelo celular: confere os bytes de verdade.
			if (!matches(mime, head(temporary))) {
				throw new AuthException(415, "O arquivo não é do formato informado.");
			}
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			temporary = null;
			return new Stored(name, written);
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao gravar a mídia", exception);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// Sobra no máximo um .tmp, que a limpeza leva.
				}
			}
		}
	}

	private static byte[] head(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return in.readNBytes(12);
		}
	}

	/** Assinatura real dos formatos aceitos. */
	static boolean matches(String mime, byte[] h) {
		if (h.length < 12) return false;
		return switch (mime) {
			case "image/webp" -> h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
			case "image/jpeg" -> (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff;
			case "image/png" -> (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
			case "audio/webm" -> (h[0] & 0xff) == 0x1a && (h[1] & 0xff) == 0x45 && (h[2] & 0xff) == 0xdf && (h[3] & 0xff) == 0xa3;
			case "audio/ogg" -> h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S';
			case "audio/mp4" -> h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
			default -> false;
		};
	}

	Optional<Path> file(String path) {
		if (path == null) return Optional.empty();
		Path target = dir.resolve(path).normalize();
		return target.startsWith(dir) && Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
	}

	/** Apaga depois do commit (arquivo não volta com rollback). */
	void removeAfterCommit(String path) {
		if (path == null) return;
		Runnable remove = () -> file(path).ifPresent(this::deleteQuietly);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					remove.run();
				}
			});
		} else {
			remove.run();
		}
	}

	void remove(String path) {
		file(path).ifPresent(this::deleteQuietly);
	}

	/** Rede de segurança: apaga arquivo da pasta com mais de TTL + 2 h (órfãos, .tmp esquecidos). */
	int sweep(Instant now) {
		if (!Files.isDirectory(dir)) return 0;
		Instant limit = now.minus(TTL).minus(Duration.ofHours(2));
		int removed = 0;
		try (var files = Files.walk(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				if (Files.getLastModifiedTime(file).toInstant().isBefore(limit) && deleteQuietly(file)) removed++;
			}
		} catch (IOException exception) {
			log.warn("Falha na limpeza das mídias: {}", exception.getMessage());
		}
		return removed;
	}

	private boolean deleteQuietly(Path file) {
		try {
			return Files.deleteIfExists(file);
		} catch (IOException exception) {
			log.warn("Falha ao apagar mídia {}: {}", dir.relativize(file), exception.getMessage());
			return false;
		}
	}
}
