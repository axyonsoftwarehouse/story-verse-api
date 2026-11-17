package com.storyverse.api.admin;

import java.util.Set;

/**
 * Confere uma imagem da loja pelos bytes reais, nunca pela extensão nem pelo tipo declarado: formato
 * (PNG, WebP e, só para capa, JPEG; nada de SVG), animação, dimensões lidas do cabeçalho e tamanho.
 * As mensagens vão direto para o admin.
 */
public final class ShopImageCheck {

	public record ImageInfo(String mime, int width, int height, boolean animated) {
	}

	public static final Set<String> KINDS = Set.of("frame", "frame_animated", "badge", "cover");

	static final long MAX_BYTES = 1024L * 1024;
	static final long MAX_ANIMATED_BYTES = 3L * 1024 * 1024;
	static final int MIN_SQUARE = 128;
	static final int MAX_SQUARE = 1024;
	static final int MIN_COVER_WIDTH = 750;
	static final int MAX_COVER_WIDTH = 3000;
	static final double COVER_RATIO = 3.0;
	static final double COVER_TOLERANCE = 0.02;

	private ShopImageCheck() {
	}

	public static ImageInfo check(byte[] bytes, String kind) {
		if (kind == null || !KINDS.contains(kind)) {
			throw new IllegalArgumentException("Tipo de imagem inválido.");
		}
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("Arquivo vazio.");
		}
		boolean animatedKind = "frame_animated".equals(kind);
		if (animatedKind && bytes.length > MAX_ANIMATED_BYTES) {
			throw new IllegalArgumentException("A moldura animada precisa ter até 3 MB.");
		}
		if (!animatedKind && bytes.length > MAX_BYTES) {
			throw new IllegalArgumentException("A imagem precisa ter até 1 MB.");
		}

		ImageInfo info = read(bytes);
		if (info == null) {
			throw new IllegalArgumentException("Formato não aceito: envie PNG, WebP ou JPEG (só na capa).");
		}
		if (animatedKind) {
			if (!"image/webp".equals(info.mime()) || !info.animated()) {
				throw new IllegalArgumentException("A moldura animada precisa ser um WebP animado.");
			}
		} else {
			if ("image/jpeg".equals(info.mime()) && !"cover".equals(kind)) {
				throw new IllegalArgumentException("Formato não aceito para este tipo: use PNG ou WebP.");
			}
			if (info.animated()) {
				throw new IllegalArgumentException(
						"Esta imagem precisa ser parada. A versão animada da moldura vai no envio de moldura animada.");
			}
		}

		if ("cover".equals(kind)) {
			double ratio = (double) info.width() / info.height();
			if (Math.abs(ratio - COVER_RATIO) / COVER_RATIO > COVER_TOLERANCE) {
				throw new IllegalArgumentException("A capa precisa ter proporção 3:1 (ex.: 1500×500).");
			}
			if (info.width() < MIN_COVER_WIDTH || info.width() > MAX_COVER_WIDTH) {
				throw new IllegalArgumentException("A capa precisa ter entre 750 e 3000 px de largura (ideal 1500×500).");
			}
		} else {
			if (info.width() != info.height()) {
				throw new IllegalArgumentException("A imagem precisa ser quadrada.");
			}
			if (info.width() < MIN_SQUARE || info.width() > MAX_SQUARE) {
				throw new IllegalArgumentException("A imagem precisa ter entre 128 e 1024 px de lado.");
			}
		}
		return info;
	}

	/** Tipo e dimensões pelo cabeçalho; null quando não é PNG, JPEG ou WebP válido. */
	static ImageInfo read(byte[] b) {
		if (isPng(b)) return readPng(b);
		if (b.length >= 3 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 && u8(b, 2) == 0xFF) return readJpeg(b);
		if (b.length >= 12 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")) return readWebp(b);
		return null;
	}

	private static boolean isPng(byte[] b) {
		return b.length >= 8 && u8(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
				&& b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
	}

	/** IHDR logo depois da assinatura; APNG (chunk acTL antes do IDAT) conta como animada. */
	private static ImageInfo readPng(byte[] b) {
		if (b.length < 33 || !ascii(b, 12, "IHDR")) return null;
		int width = be32(b, 16);
		int height = be32(b, 20);
		if (width <= 0 || height <= 0) return null;
		boolean animated = false;
		int offset = 8;
		while (offset + 8 <= b.length) {
			long length = be32(b, offset) & 0xFFFFFFFFL;
			if (ascii(b, offset + 4, "acTL")) {
				animated = true;
				break;
			}
			if (ascii(b, offset + 4, "IDAT") || ascii(b, offset + 4, "IEND")) break;
			long next = offset + 12L + length;
			if (next > b.length) break;
			offset = (int) next;
		}
		return new ImageInfo("image/png", width, height, animated);
	}

	/** Procura o marcador SOF (C0–CF, menos C4, C8 e CC), onde ficam altura e largura. */
	private static ImageInfo readJpeg(byte[] b) {
		int offset = 2;
		while (offset + 4 <= b.length) {
			if (u8(b, offset) != 0xFF) return null;
			int marker = u8(b, offset + 1);
			if (marker == 0xFF) {
				offset++;
				continue;
			}
			if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				offset += 2;
				continue;
			}
			if (marker == 0xD9 || marker == 0xDA) return null;
			int length = be16(b, offset + 2);
			if (length < 2) return null;
			boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
			if (sof) {
				if (offset + 9 > b.length) return null;
				int height = be16(b, offset + 5);
				int width = be16(b, offset + 7);
				if (width <= 0 || height <= 0) return null;
				return new ImageInfo("image/jpeg", width, height, false);
			}
			offset += 2 + length;
		}
		return null;
	}

	/** Primeiro chunk: VP8 (com perdas), VP8L (sem perdas) ou VP8X (estendido, com flag de animação). */
	private static ImageInfo readWebp(byte[] b) {
		if (ascii(b, 12, "VP8X")) {
			if (b.length < 30) return null;
			boolean animated = (u8(b, 20) & 0x02) != 0 || hasChunk(b, "ANIM");
			int width = le24(b, 24) + 1;
			int height = le24(b, 27) + 1;
			return new ImageInfo("image/webp", width, height, animated);
		}
		if (ascii(b, 12, "VP8L")) {
			if (b.length < 25 || u8(b, 20) != 0x2F) return null;
			int bits = le32(b, 21);
			int width = (bits & 0x3FFF) + 1;
			int height = ((bits >>> 14) & 0x3FFF) + 1;
			return new ImageInfo("image/webp", width, height, false);
		}
		if (ascii(b, 12, "VP8 ")) {
			if (b.length < 30 || u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null;
			int width = le16(b, 26) & 0x3FFF;
			int height = le16(b, 28) & 0x3FFF;
			if (width <= 0 || height <= 0) return null;
			return new ImageInfo("image/webp", width, height, false);
		}
		return null;
	}

	private static boolean hasChunk(byte[] b, String fourcc) {
		int offset = 12;
		while (offset + 8 <= b.length) {
			if (ascii(b, offset, fourcc)) return true;
			long size = le32(b, offset + 4) & 0xFFFFFFFFL;
			long next = offset + 8L + size + (size & 1);
			if (next > b.length) return false;
			offset = (int) next;
		}
		return false;
	}

	private static boolean ascii(byte[] b, int offset, String text) {
		if (offset < 0 || offset + text.length() > b.length) return false;
		for (int i = 0; i < text.length(); i++) {
			if (b[offset + i] != (byte) text.charAt(i)) return false;
		}
		return true;
	}

	private static int u8(byte[] b, int i) {
		return b[i] & 0xFF;
	}

	private static int be16(byte[] b, int i) {
		return (u8(b, i) << 8) | u8(b, i + 1);
	}

	private static int be32(byte[] b, int i) {
		return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
	}

	private static int le16(byte[] b, int i) {
		return u8(b, i) | (u8(b, i + 1) << 8);
	}

	private static int le24(byte[] b, int i) {
		return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16);
	}

	private static int le32(byte[] b, int i) {
		return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16) | (u8(b, i + 3) << 24);
	}
}
