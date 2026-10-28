package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storyverse.api.auth.AuthException;

class CatalogImportArchiveTest {

	@TempDir
	Path temp;

	@Test
	void extraiMetadataEArquivos() throws Exception {
		byte[] zip = zip(
				entry("metadata.csv", "file,title,rights\nlivros/a.txt,Livro,public_domain\n"),
				entry("livros/a.txt", "Era uma vez"));
		CatalogImportArchive.Extracted extracted =
				new CatalogImportArchive().extract(zip, Files.createTempDirectory(temp, "import-"));

		assertTrue(Files.isRegularFile(extracted.metadata()));
		assertEquals("Era uma vez", Files.readString(CatalogImportArchive.file(extracted.files(), "livros/a.txt")));
	}

	@Test
	void recusaCaminhoQueEscapaDaPasta() throws Exception {
		byte[] zip = zip(
				entry("metadata.csv", "file,title,rights\nok.txt,Livro,public_domain\n"),
				entry("ok.txt", "texto"),
				entry("../fora.txt", "texto"));
		assertThrows(AuthException.class,
				() -> new CatalogImportArchive().extract(zip, Files.createTempDirectory(temp, "import-")));
	}

	@Test
	void exigeMetadata() throws Exception {
		byte[] zip = zip(entry("livro.txt", "texto"));
		assertThrows(AuthException.class,
				() -> new CatalogImportArchive().extract(zip, Files.createTempDirectory(temp, "import-")));
	}

	private static String[] entry(String name, String content) {
		return new String[] { name, content };
	}

	private static byte[] zip(String[]... entries) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String[] entry : entries) {
				zip.putNextEntry(new ZipEntry(entry[0]));
				zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}
}
