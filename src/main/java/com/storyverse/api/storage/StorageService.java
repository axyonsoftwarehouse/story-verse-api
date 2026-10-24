package com.storyverse.api.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.storyverse.api.auth.AuthException;

/**
 * Armazenamento em disco local na VPS (Marco 2). As regras de acesso ficam aqui (não no storage):
 * cada pessoa só escreve/lê a própria pasta; `profile-media` tem leitura pública.
 */
@Service
public class StorageService {

	private static final Logger log = LoggerFactory.getLogger(StorageService.class);

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
		write(userId, bucket, path, contentType, new java.io.ByteArrayInputStream(bytes), bytes.length);
	}

	/** Grava em streaming: o corpo não é materializado inteiro em memória. */
	@Transactional
	public void write(UUID userId, StorageBucket bucket, String path, String contentType,
			java.io.InputStream input, long declaredLength) {
		String own = requireOwn(userId, path);
		String type = normalizeType(contentType, own);
		if (!bucket.allowedTypes().contains(type)) {
			throw new AuthException(415, "Formato de arquivo não aceito.");
		}
		if (declaredLength == 0) {
			throw new AuthException(400, "Arquivo vazio.");
		}
		if (declaredLength > bucket.maxBytes()) {
			throw new AuthException(413, "Arquivo grande demais.");
		}
		Path target = resolve(bucket, own);
		access.lock("storage-capacity");
		access.lock("storage-owner:" + userId);
		access.setUser(userId);
		if (bucket == StorageBucket.COMMUNITY_BOOKS) access.lockCommunity(own);
		access.assertWritable(bucket, own);
		Path temporary = null;
		try {
			Files.createDirectories(baseDir);
			long expected = declaredLength > 0 ? declaredLength : bucket.maxBytes();
			if (Files.getFileStore(baseDir).getUsableSpace() - expected < 2L * 1024 * 1024 * 1024)
				throw new AuthException(507, "Armazenamento temporariamente indisponível.");
			ensureQuota(userId, target, expected);
			Files.createDirectories(target.getParent());
			Path temporaryDir = baseDir.resolve(".tmp");
			Files.createDirectories(temporaryDir);
			temporary = Files.createTempFile(temporaryDir, ".upload-", ".tmp");
			long written = copyBounded(input, temporary, bucket.maxBytes());
			if (written == 0) {
				throw new AuthException(400, "Arquivo vazio.");
			}
			// O Content-Type é declarado pelo cliente: confere os bytes reais antes de publicar.
			validateContent(temporary, type);
			// O Content-Length pode mentir: reconfere a quota com o tamanho realmente recebido.
			ensureQuota(userId, target, written);
			Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			temporary = null;
		} catch (IOException exception) {
			throw new UncheckedIOException("Falha ao gravar o arquivo", exception);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
			}
		}
	}

	private void ensureQuota(UUID userId, Path target, long incoming) throws IOException {
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
		if (total - (existing ? Files.size(target) : 0) + incoming > quotaBytes
				|| count + (existing ? 0 : 1) > quotaObjects)
			throw new AuthException(413, "Limite de armazenamento da conta atingido.");
	}

	/**
	 * Confere a assinatura real do arquivo. Impede que um cliente direto envie HTML/JS/binário
	 * rotulado como imagem ou texto e ele seja servido com o tipo declarado.
	 */
	static void validateContent(Path file, String type) throws IOException {
		byte[] head = new byte[12];
		int n;
		try (java.io.InputStream input = Files.newInputStream(file)) {
			n = input.readNBytes(head, 0, head.length);
		}
		boolean ok = switch (type) {
			case "image/jpeg" -> n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
					&& (head[2] & 0xFF) == 0xFF;
			case "image/png" -> n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
					&& head[3] == 'G' && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
			case "image/webp" -> n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F'
					&& head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
			case "application/gzip" -> n >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
			case "application/pdf" -> n >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D'
					&& head[3] == 'F' && head[4] == '-';
			case "application/epub+zip" -> n >= 4 && head[0] == 'P' && head[1] == 'K'
					&& head[2] == 0x03 && head[3] == 0x04;
			case "text/plain" -> isText(file);
			default -> false;
		};
		if (!ok) throw new AuthException(415, "O conteúdo do arquivo não corresponde ao formato.");
	}

	private static boolean isText(Path file) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		try (java.io.InputStream input = Files.newInputStream(file)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					int value = buffer[i] & 0xFF;
					if (value == 0 || value == 0x7F
							|| value < 0x20 && value != '\t' && value != '\n' && value != '\r' && value != '\f') {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static long copyBounded(java.io.InputStream input, Path target, long maxBytes) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long total = 0;
		try (java.io.OutputStream out = Files.newOutputStream(target,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) throw new AuthException(413, "Arquivo grande demais.");
				out.write(buffer, 0, read);
			}
		}
		return total;
	}

	/** Caminho do arquivo autorizado; o controller entrega como Resource (streaming). */
	@Transactional(readOnly = true)
	public Optional<Path> fileFor(UUID userId, StorageBucket bucket, String path) {
		String cleaned = safePath(path);
		if (!access.readable(userId, bucket, cleaned)) throw new AuthException(403, "Sem permissão para este arquivo.");
		return file(bucket, cleaned);
	}

	public boolean exists(StorageBucket bucket, String path) { return Files.isRegularFile(resolve(bucket, safePath(path))); }

	/** Caminho de um arquivo público/autenticado, sem carregar o conteúdo em memória. */
	public Optional<Path> file(StorageBucket bucket, String path) {
		Path target = resolve(bucket, safePath(path));
		return Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
	}

	/** Leitura em memória (testes e chamadas internas); o controller usa `fileFor`. */
	@Transactional(readOnly = true)
	public Optional<byte[]> readFor(UUID userId, StorageBucket bucket, String path) {
		return fileFor(userId, bucket, path).map(StorageService::readBytes);
	}

	/** Leitura em memória de um arquivo (público ou autenticado). */
	public Optional<byte[]> read(StorageBucket bucket, String path) {
		return file(bucket, path).map(StorageService::readBytes);
	}

	private static byte[] readBytes(Path target) {
		try {
			return Files.readAllBytes(target);
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

	/**
	 * Apaga um arquivo só depois do commit da transação atual (fora de transação, apaga na hora). Não
	 * confere permissão por pessoa: quem chama já conferiu e o caminho vem do banco. Arquivo não volta
	 * com rollback, então uma falha aqui vira aviso no log e deixa um arquivo órfão, nunca uma linha órfã.
	 */
	public void removeAfterCommit(StorageBucket bucket, String path) {
		Path target;
		try {
			target = resolve(bucket, safePath(path));
		} catch (AuthException exception) {
			log.warn("Caminho inválido ao agendar remoção em {}: {}", bucket.id(), path);
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					removeQuietly(bucket, target);
				}
			});
		} else {
			removeQuietly(bucket, target);
		}
	}

	private void removeQuietly(StorageBucket bucket, Path target) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException | RuntimeException exception) {
			log.warn("Falha ao apagar arquivo em {}: {}", bucket.id(),
					baseDir.resolve(bucket.id()).relativize(target), exception);
		}
	}

	/** Remove todos os arquivos de todas as pastas da conta (exclusão de conta). */
	@Transactional
	public void deleteAllForUser(UUID userId) {
		access.lock("storage-owner:" + userId);
		for (StorageBucket bucket : StorageBucket.values()) {
			Path folder = baseDir.resolve(bucket.id()).resolve(userId.toString());
			if (!Files.exists(folder)) continue;
			try (var paths = Files.walk(folder)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			} catch (IOException exception) {
				throw new UncheckedIOException("Falha ao apagar os arquivos da conta", exception);
			}
		}
	}

	/** Exporta todos os arquivos da conta em um ZIP (portabilidade de dados). */
	public void writeExportZip(UUID userId, OutputStream output) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			for (StorageBucket bucket : StorageBucket.values()) {
				Path folder = baseDir.resolve(bucket.id()).resolve(userId.toString());
				if (!Files.exists(folder)) continue;
				List<Path> files;
				try (var paths = Files.walk(folder)) {
					files = paths.filter(Files::isRegularFile).toList();
				}
				for (Path file : files) {
					String relative = folder.relativize(file).toString().replace('\\', '/');
					zip.putNextEntry(new ZipEntry(bucket.id() + "/" + relative));
					Files.copy(file, zip);
					zip.closeEntry();
				}
			}
			zip.finish();
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
