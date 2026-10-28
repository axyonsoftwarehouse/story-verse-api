package com.storyverse.api.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.storyverse.api.auth.AuthException;

/**
 * Abre o ZIP da importação num diretório temporário. Limita entradas e tamanho descompactado para
 * o pacote não derrubar a VPS e recusa caminhos que escapariam da pasta temporária.
 */
@Component
public class CatalogImportArchive {

	static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;
	static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
	static final int MAX_ENTRIES = 300;
	private static final List<String> BOOK_EXTENSIONS = List.of(".txt", ".md", ".markdown", ".epub", ".pdf");

	public record Extracted(Path directory, Map<String, Path> files, Path metadata) {
	}

	public Extracted extract(byte[] zipBytes, Path directory) {
		if (zipBytes == null || zipBytes.length == 0) throw new AuthException(400, "O arquivo ZIP está vazio.");
		return extract(new java.io.ByteArrayInputStream(zipBytes), directory);
	}

	/** Lê o ZIP do disco em streaming (um lote de 200 MB não precisa caber inteiro na memória). */
	public Extracted extract(Path zipPath, Path directory) {
		try (InputStream input = new java.io.BufferedInputStream(Files.newInputStream(zipPath))) {
			return extract(input, directory);
		} catch (AuthException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new AuthException(400, "Não foi possível abrir o ZIP.");
		}
	}

	private Extracted extract(InputStream input, Path directory) {
		Map<String, Path> files = new LinkedHashMap<>();
		Path metadata = null;
		long total = 0;
		int entries = 0;
		try (ZipInputStream zip = new ZipInputStream(input)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				if (++entries > MAX_ENTRIES) throw new AuthException(413, "O ZIP tem arquivos demais.");
				String name = safeName(entry.getName());
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.startsWith("__macosx/") || lower.endsWith(".ds_store")) continue;
				boolean isMetadata = lower.endsWith("metadata.csv");
				boolean isBook = BOOK_EXTENSIONS.stream().anyMatch(lower::endsWith);
				if (!isMetadata && !isBook) continue;

				Path target = directory.resolve(name).normalize();
				if (!target.startsWith(directory)) throw new AuthException(400, "Caminho inválido no ZIP.");
				Files.createDirectories(target.getParent());
				long written = copy(zip, target, MAX_FILE_BYTES);
				total += written;
				if (total > MAX_TOTAL_BYTES) throw new AuthException(413, "O conteúdo do ZIP é grande demais.");
				if (isMetadata && metadata == null) metadata = target;
				else if (isBook) files.put(name, target);
			}
		} catch (AuthException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new AuthException(400, "Não foi possível abrir o ZIP.");
		}
		if (metadata == null) throw new AuthException(400, "O ZIP precisa conter um metadata.csv.");
		return new Extracted(directory, files, metadata);
	}

	/** Busca o arquivo pelo nome do CSV sem diferenciar maiúsculas/minúsculas. */
	public static Path file(Map<String, Path> files, String name) {
		Path direct = files.get(name);
		if (direct != null) return direct;
		for (Map.Entry<String, Path> entry : files.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
		}
		return null;
	}

	private static String safeName(String name) {
		String normalized = name.replace('\\', '/');
		if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
			throw new AuthException(400, "Caminho inválido no ZIP.");
		}
		if (normalized.length() > 300) throw new AuthException(400, "Nome de arquivo grande demais no ZIP.");
		return normalized;
	}

	private static long copy(InputStream input, Path target, long max) throws IOException {
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		try (OutputStream output = Files.newOutputStream(target)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > max) throw new AuthException(413, "Um arquivo do ZIP é grande demais.");
				output.write(buffer, 0, read);
			}
		}
		return total;
	}
}
