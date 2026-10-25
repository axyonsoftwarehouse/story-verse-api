package com.storyverse.api.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.storyverse.api.auth.AuthException;

/** Extrai texto e, quando o arquivo traz, título/autor/idioma de TXT, PDF e EPUB. */
@Component
public class CatalogTextExtractor {

	public record Extracted(String text, int pageCount, String title, String author, String language) {
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
		return new Extracted(requireText(text), pages(text.length()), null, null, null);
	}

	private Extracted pdf(byte[] bytes) throws IOException {
		try (PDDocument document = Loader.loadPDF(bytes)) {
			String text = normalize(new PDFTextStripper().getText(document));
			int pages = Math.max(1, document.getNumberOfPages());
			PDDocumentInformation info = document.getDocumentInformation();
			return new Extracted(requireText(text), pages, clean(info.getTitle()), clean(info.getAuthor()), null);
		}
	}

	private Extracted epub(byte[] bytes) throws IOException {
		List<String> names = new ArrayList<>();
		List<String> chapters = new ArrayList<>();
		String opf = null;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				String lower = name.toLowerCase(Locale.ROOT);
				if (entry.isDirectory()) continue;
				if (lower.equals("meta-inf/container.xml")) {
					// O container aponta para o OPF; o primeiro .opf do ZIP já basta para os metadados.
					continue;
				}
				if (lower.endsWith(".opf")) {
					if (opf == null) opf = new String(readAll(zip), StandardCharsets.UTF_8);
					continue;
				}
				if (!(lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
					continue;
				}
				names.add(name);
				chapters.add(new String(readAll(zip), StandardCharsets.UTF_8));
			}
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) order.add(i);
		order.sort(Comparator.comparing(i -> names.get(i).toLowerCase(Locale.ROOT)));
		StringBuilder text = new StringBuilder();
		for (int index : order) {
			String chapter = Jsoup.parse(chapters.get(index)).text().strip();
			if (chapter.isEmpty()) continue;
			if (text.length() > 0) text.append("\n\n");
			text.append(chapter);
		}
		String normalized = normalize(text.toString());
		Meta meta = opf == null ? null : meta(opf);
		return new Extracted(requireText(normalized), pages(normalized.length()),
				meta == null ? null : meta.title(), meta == null ? null : meta.author(),
				meta == null ? null : meta.language());
	}

	private record Meta(String title, String author, String language) {
	}

	private static Meta meta(String opf) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setExpandEntityReferences(false);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			Document document = factory.newDocumentBuilder()
					.parse(new InputSource(new StringReader(opf)));
			String title = clean(first(document, "title"));
			String author = clean(first(document, "creator"));
			String language = normalizeLanguage(clean(first(document, "language")));
			return new Meta(title, author, language);
		} catch (Exception error) {
			return null;
		}
	}

	private static String first(Document document, String tag) {
		NodeList nodes = document.getElementsByTagNameNS("*", tag);
		if (nodes.getLength() == 0) nodes = document.getElementsByTagName(tag);
		if (nodes.getLength() == 0) return null;
		return nodes.item(0).getTextContent();
	}

	private static String normalizeLanguage(String value) {
		if (value == null) return null;
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("en")) return "en";
		if (lower.startsWith("pt")) return "pt";
		return null;
	}

	private static String clean(String value) {
		if (value == null || value.isBlank()) return null;
		return value.strip();
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
