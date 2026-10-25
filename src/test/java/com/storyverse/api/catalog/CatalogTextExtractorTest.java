package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class CatalogTextExtractorTest {

	private final CatalogTextExtractor extractor = new CatalogTextExtractor();

	@Test
	void extractsPlainText() {
		CatalogTextExtractor.Extracted extracted = extractor.extract(
				"\uFEFFPrimeira linha\r\nSegunda linha".getBytes(StandardCharsets.UTF_8), "text/plain");
		assertEquals("Primeira linha\nSegunda linha", extracted.text());
		assertEquals(1, extracted.pageCount());
	}

	@Test
	void extractsEpubChapters() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
			zip.write("<html><body><p>Olá</p><p>Mundo</p></body></html>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
			zip.write("<container/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		CatalogTextExtractor.Extracted extracted = extractor.extract(bytes.toByteArray(),
				"application/epub+zip");
		assertEquals("Olá Mundo", extracted.text());
	}

	@Test
	void readsEpubTitleAuthorAndLanguage() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
			zip.write("""
					<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
					""".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
			zip.write("""
					<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
					<metadata><dc:title>Coelho maldito</dc:title><dc:creator>Bora Chung</dc:creator>
					<dc:language>pt-BR</dc:language></metadata></package>
					""".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
			zip.write("<html><body><p>Era uma vez um coelho.</p></body></html>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		CatalogTextExtractor.Extracted extracted = extractor.extract(bytes.toByteArray(),
				"application/epub+zip");
		assertEquals("Coelho maldito", extracted.title());
		assertEquals("Bora Chung", extracted.author());
		assertEquals("pt", extracted.language());
	}

	@Test
	void extractsPdfTextAndPages() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
				stream.beginText();
				stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				stream.newLineAtOffset(50, 700);
				stream.showText("Hello catalog");
				stream.endText();
			}
			document.getDocumentInformation().setTitle("Cat?logo PDF");
			document.getDocumentInformation().setAuthor("Equipe QA");
			document.save(bytes);
		}
		CatalogTextExtractor.Extracted extracted = extractor.extract(bytes.toByteArray(), "application/pdf");
		assertTrue(extracted.text().contains("Hello catalog"));
		assertEquals(1, extracted.pageCount());
		assertEquals("Cat?logo PDF", extracted.title());
		assertEquals("Equipe QA", extracted.author());
	}
}
