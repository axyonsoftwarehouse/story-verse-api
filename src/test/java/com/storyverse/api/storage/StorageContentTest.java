package com.storyverse.api.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storyverse.api.auth.AuthException;

class StorageContentTest {

	@TempDir
	Path temp;

	@Test
	void acceptsRealImageSignatures() throws Exception {
		Path jpeg = temp.resolve("a.jpg");
		Files.write(jpeg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01});
		assertDoesNotThrow(() -> StorageService.validateContent(jpeg, "image/jpeg"));

		Path png = temp.resolve("a.png");
		Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00});
		assertDoesNotThrow(() -> StorageService.validateContent(png, "image/png"));

		Path webp = temp.resolve("a.webp");
		Files.write(webp, "RIFFxxxxWEBP".getBytes(StandardCharsets.US_ASCII));
		assertDoesNotThrow(() -> StorageService.validateContent(webp, "image/webp"));
	}

	@Test
	void rejectsBytesThatDoNotMatchTheDeclaredType() throws Exception {
		Path file = temp.resolve("fake.png");
		Files.write(file, "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

		AuthException error = assertThrows(AuthException.class,
				() -> StorageService.validateContent(file, "image/png"));
		assertEquals(415, error.status());
	}

	@Test
	void acceptsUtf8TextAndGzipButRejectsBinaryText() throws Exception {
		Path text = temp.resolve("book.txt");
		Files.writeString(text, "Capítulo 1\n\nEra uma vez…\n", StandardCharsets.UTF_8);
		assertDoesNotThrow(() -> StorageService.validateContent(text, "text/plain"));

		Path gzip = temp.resolve("book.gz");
		Files.write(gzip, new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00});
		assertDoesNotThrow(() -> StorageService.validateContent(gzip, "application/gzip"));

		Path binary = temp.resolve("binary.txt");
		Files.write(binary, new byte[]{'a', 0x00, 'b'});
		AuthException error = assertThrows(AuthException.class,
				() -> StorageService.validateContent(binary, "text/plain"));
		assertEquals(415, error.status());
	}
}
