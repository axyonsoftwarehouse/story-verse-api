package com.storyverse.api.push;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push só com a JDK: cifra a mensagem para o navegador (RFC 8291, aes128gcm) e assina o
 * cabeçalho VAPID (RFC 8292, ES256). Sem biblioteca de terceiros.
 */
final class WebPushCrypto {

	/** Tamanho do registro declarado no cabeçalho (a mensagem cabe num registro só). */
	static final int RECORD_SIZE = 4096;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final ECParameterSpec P256 = p256();
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64D = Base64.getUrlDecoder();

	private WebPushCrypto() {
	}

	private static ECParameterSpec p256() {
		try {
			return ((ECPublicKey) generate().getPublic()).getParams();
		} catch (RuntimeException exception) {
			throw new IllegalStateException("P-256 indisponível na JDK", exception);
		}
	}

	static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** Ponto público não comprimido (65 bytes: 0x04 || X || Y), o formato dos navegadores. */
	static byte[] rawPublic(ECPublicKey key) {
		byte[] out = new byte[65];
		out[0] = 4;
		System.arraycopy(unsigned32(key.getW().getAffineX()), 0, out, 1, 32);
		System.arraycopy(unsigned32(key.getW().getAffineY()), 0, out, 33, 32);
		return out;
	}

	static ECPublicKey publicFromRaw(byte[] raw) {
		if (raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Chave pública inválida.");
		try {
			BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
			BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
			return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
		} catch (GeneralSecurityException exception) {
			throw new IllegalArgumentException("Chave pública inválida.", exception);
		}
	}

	static PrivateKey privateFromPkcs8(byte[] encoded) {
		try {
			return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Chave VAPID inválida.", exception);
		}
	}

	/** Cifra para o navegador: cabeçalho (sal, tamanho do registro, chave efêmera) + texto cifrado. */
	static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		return encrypt(plaintext, uaPublic, authSecret, generate(), salt);
	}

	static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair server, byte[] salt) {
		if (authSecret.length != 16) throw new IllegalArgumentException("Segredo de autenticação inválido.");
		if (plaintext.length > RECORD_SIZE - 17 - 86) throw new IllegalArgumentException("Mensagem grande demais.");
		try {
			byte[] serverPublic = rawPublic((ECPublicKey) server.getPublic());
			KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
			agreement.init(server.getPrivate());
			agreement.doPhase(publicFromRaw(uaPublic), true);
			byte[] shared = agreement.generateSecret();

			// RFC 8291 §3.4: o segredo de autenticação entra na derivação da chave.
			byte[] prkKey = hmac(authSecret, shared);
			byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, serverPublic, new byte[] { 1 }));
			// RFC 8188: chave de conteúdo (16 bytes) e nonce (12 bytes) a partir do sal.
			byte[] prk = hmac(salt, ikm);
			byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 })), 16);
			byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 })), 12);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
			// 0x02: delimitador do último (e único) registro.
			byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] { 2 }));

			return ByteBuffer.allocate(16 + 4 + 1 + serverPublic.length + ciphertext.length)
					.put(salt)
					.putInt(RECORD_SIZE)
					.put((byte) serverPublic.length)
					.put(serverPublic)
					.put(ciphertext)
					.array();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Falha ao cifrar a notificação.", exception);
		}
	}

	/** Cabeçalho Authorization do VAPID: JWT assinado (ES256) + chave pública. */
	static String vapidAuthorization(String endpoint, String subject, long expiresAtEpochSeconds, PrivateKey key, byte[] publicRaw) {
		URI uri = URI.create(endpoint);
		String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
		String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
		String claims = b64(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + expiresAtEpochSeconds + ",\"sub\":\"" + json(subject) + "\"}")
				.getBytes(StandardCharsets.UTF_8));
		try {
			// P1363: assinatura crua R||S (64 bytes), como o JWT pede (não DER).
			Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
			signature.initSign(key);
			signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
			String jwt = header + "." + claims + "." + b64(signature.sign());
			return "vapid t=" + jwt + ", k=" + b64(publicRaw);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Falha ao assinar o VAPID.", exception);
		}
	}

	static String b64(byte[] bytes) {
		return B64.encodeToString(bytes);
	}

	static byte[] unb64(String value) {
		return B64D.decode(value.replace('+', '-').replace('/', '_').replace("=", ""));
	}

	static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data);
	}

	private static byte[] unsigned32(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes.length == 32) return bytes;
		byte[] out = new byte[32];
		if (bytes.length > 32) System.arraycopy(bytes, bytes.length - 32, out, 0, 32);
		else System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length);
		return out;
	}

	private static byte[] concat(byte[]... parts) {
		int size = 0;
		for (byte[] part : parts) size += part.length;
		ByteBuffer buffer = ByteBuffer.allocate(size);
		for (byte[] part : parts) buffer.put(part);
		return buffer.array();
	}

	private static String json(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
