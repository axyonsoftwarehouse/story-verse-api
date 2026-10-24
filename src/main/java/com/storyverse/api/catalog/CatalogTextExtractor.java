package com.storyverse.api.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import com.storyverse.api.auth.AuthException;

/** Extrai texto de TXT/MD, PDF e EPUB. O original fica no storage; o texto vai para o leitor. */
@Component
public class CatalogTextExtractor {

	public record Extracted(String text, int pageCount) {
	}

	public Extracted extract(byte[] bytes, String contentType) {
		String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase();
		try {
			return switch (type) {
				case "text/plain", "text/markdown" -> text(bytes);
				case "application/pdf" -> pdf(bytes);
				case "application/epub+zip" -> epub(bytes);
				default -> throw new AuthException(415, "Formato de arquivo não aceito.");
			};
		} catch (AuthException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new AuthException(400, "Não foi possível extrair o texto do arquivo.");
		}
	}

	private Extracted text(byte[] bytes) {
		String text = normalize(new String(bytes, StandardCharsets.UTF_8));
		return new Extracted(requireText(text), pages(text.length()));
	}

	private Extracted pdf(byte[] bytes) throws IOException {
		try (PDDocument document = Loader.loadPDF(bytes)) {
			String text = normalize(new PDFTextStripper().getText(document));
			int pages = Math.max(1, document.getNumberOfPages());
			return new Extracted(requireText(text), pages);
		}
	}

	private Extracted epub(byte[] bytes) throws IOException {
		List<String> names = new ArrayList<>();
		List<String> chapters = new ArrayList<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				String lower = name.toLowerCase();
				if (entry.isDirectory() || lower.startsWith("meta-inf/")
						|| !(lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
					continue;
				}
				names.add(name);
				chapters.add(new String(readAll(zip), StandardCharsets.UTF_8));
			}
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) order.add(i);
		order.sort(Comparator.comparing(i -> names.get(i).toLowerCase()));
		StringBuilder text = new StringBuilder();
		for (int index : order) {
			String chapter = Jsoup.parse(chapters.get(index)).text().strip();
			if (chapter.isEmpty()) continue;
			if (text.length() > 0) text.append("\n\n");
			text.append(chapter);
		}
		String normalized = normalize(text.toString());
		return new Extracted(requireText(normalized), pages(normalized.length()));
	}

	private static byte[] readAll(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		zip.transferTo(out);
		return out.toByteArray();
	}

	private static String normalize(String value) {
		String text = value == null ? "" : value;
		if (text.startsWith("\uFEFF")) text = text.substring(1);
		return text.replace("\r\n", "\n").replace('\r', '\n').strip();
	}

	private static String requireText(String text) {
		if (text.isBlank()) throw new AuthException(400, "O arquivo não tem texto legível.");
		if (text.length() > 10 * 1024 * 1024) throw new AuthException(413, "Texto grande demais.");
		return text;
	}

	private static int pages(int chars) {
		return Math.max(1, (int) Math.ceil(chars / 1800.0));
	}
}
