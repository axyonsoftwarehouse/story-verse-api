package com.storyverse.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** Conferência das imagens da loja pelos bytes reais (tipo, animação, dimensões e tamanho). */
class ShopImageCheckTest {

	static byte[] png(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	static byte[] jpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	/** WebP estendido (VP8X) com a flag de animação, um chunk ANIM e um quadro ANMF. */
	static byte[] animatedWebp(int width, int height) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes("VP8X".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(le32(10));
		body.write(0x12); // animação + alfa
		body.writeBytes(new byte[3]);
		body.writeBytes(le24(width - 1));
		body.writeBytes(le24(height - 1));
		body.writeBytes("ANIM".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(le32(6));
		body.writeBytes(new byte[6]);
		body.writeBytes("ANMF".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(le32(16));
		body.writeBytes(new byte[16]);
		return riff(body.toByteArray());
	}

	/** WebP sem perdas (VP8L), parado. */
	static byte[] losslessWebp(int width, int height) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes("VP8L".getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(le32(5));
		body.write(0x2F);
		int bits = (width - 1) | ((height - 1) << 14) | (1 << 28);
		body.writeBytes(le32(bits));
		body.write(0); // padding para tamanho par
		return riff(body.toByteArray());
	}

	static byte[] riff(byte[] body) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(le32(body.length));
		out.writeBytes(body);
		return out.toByteArray();
	}

	static byte[] le32(int value) {
		return new byte[] { (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24) };
	}

	static byte[] le24(int value) {
		return new byte[] { (byte) value, (byte) (value >> 8), (byte) (value >> 16) };
	}

	static String refused(byte[] bytes, String kind) {
		return assertThrows(IllegalArgumentException.class, () -> ShopImageCheck.check(bytes, kind)).getMessage();
	}

	@Test
	void squarePngIsAcceptedForBadgeAndFrame() throws IOException {
		ShopImageCheck.ImageInfo info = ShopImageCheck.check(png(256, 256), "badge");
		assertEquals("image/png", info.mime());
		assertEquals(256, info.width());
		assertEquals(256, info.height());
		assertFalse(info.animated());
		assertEquals("image/png", ShopImageCheck.check(png(256, 256), "frame").mime());
	}

	@Test
	void animatedWebpIsAcceptedAsAnimatedFrame() {
		ShopImageCheck.ImageInfo info = ShopImageCheck.check(animatedWebp(512, 512), "frame_animated");
		assertEquals("image/webp", info.mime());
		assertEquals(512, info.width());
		assertEquals(512, info.height());
		assertTrue(info.animated());
	}

	@Test
	void animatedFrameMustReallyBeAnimatedWebp() throws IOException {
		assertTrue(refused(losslessWebp(256, 256), "frame_animated").contains("WebP animado"));
		assertTrue(refused(png(256, 256), "frame_animated").contains("WebP animado"));
	}

	@Test
	void staticKindsRefuseAnimatedImages() {
		assertTrue(refused(animatedWebp(256, 256), "frame").contains("parada"));
		assertTrue(refused(animatedWebp(256, 256), "badge").contains("parada"));
	}

	@Test
	void losslessWebpReadsDimensions() {
		ShopImageCheck.ImageInfo info = ShopImageCheck.check(losslessWebp(300, 300), "badge");
		assertEquals("image/webp", info.mime());
		assertEquals(300, info.width());
		assertEquals(300, info.height());
	}

	@Test
	void svgRenamedAsPngIsRefused() {
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
				.getBytes(StandardCharsets.UTF_8);
		assertTrue(refused(svg, "badge").contains("Formato não aceito"));
		assertTrue(refused(svg, "cover").contains("Formato não aceito"));
	}

	@Test
	void badgeMustBeSquare() throws IOException {
		assertTrue(refused(png(300, 200), "badge").contains("precisa ser quadrada"));
	}

	@Test
	void squareImagesMustFitTheSizeRange() throws IOException {
		assertTrue(refused(png(100, 100), "badge").contains("128"));
		assertTrue(refused(png(1100, 1100), "frame").contains("1024"));
	}

	@Test
	void jpegIsRefusedForFrame() throws IOException {
		assertTrue(refused(jpeg(256, 256), "frame").contains("Formato não aceito"));
		assertTrue(refused(jpeg(256, 256), "badge").contains("Formato não aceito"));
	}

	@Test
	void coverNeedsThreeToOneRatio() throws IOException {
		ShopImageCheck.ImageInfo info = ShopImageCheck.check(jpeg(1500, 500), "cover");
		assertEquals("image/jpeg", info.mime());
		assertEquals(1500, info.width());
		assertEquals(500, info.height());
		// ±2% de tolerância na proporção.
		ShopImageCheck.check(png(1500, 510), "cover");
		assertTrue(refused(png(1500, 800), "cover").contains("proporção 3:1"));
	}

	@Test
	void badgeOverOneMegabyteIsRefused() throws IOException {
		byte[] base = png(256, 256);
		byte[] big = Arrays.copyOf(base, (int) (1.2 * 1024 * 1024));
		assertTrue(refused(big, "badge").contains("até 1 MB"));
	}

	@Test
	void unknownKindIsRefused() throws IOException {
		assertTrue(refused(png(256, 256), "avatar").contains("Tipo de imagem"));
	}
}
