package com.storyverse.api.push;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * Faz o papel do navegador: decifra o que a API cifrou (RFC 8291) e confere a assinatura do
 * VAPID (RFC 8292). Se a derivação estiver errada, o texto não abre.
 */
class WebPushCryptoTest {

	@Test
	void browserDecryptsWhatTheApiEncrypted() throws Exception {
		KeyPair browser = WebPushCrypto.generate();
		byte[] browserPublic = WebPushCrypto.rawPublic((ECPublicKey) browser.getPublic());
		byte[] authSecret = new byte[16];
		new SecureRandom().nextBytes(authSecret);
		byte[] message = "{\"title\":\"Clube\",\"body\":\"Ana: oi 😊\"}".getBytes(StandardCharsets.UTF_8);

		byte[] wire = WebPushCrypto.encrypt(message, browserPublic, authSecret);

		ByteBuffer in = ByteBuffer.wrap(wire);
		byte[] salt = new byte[16];
		in.get(salt);
		assertEquals(WebPushCrypto.RECORD_SIZE, in.getInt());
		byte[] serverPublic = new byte[in.get()];
		in.get(serverPublic);
		byte[] ciphertext = new byte[in.remaining()];
		in.get(ciphertext);

		KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(browser.getPrivate());
		agreement.doPhase(WebPushCrypto.publicFromRaw(serverPublic), true);
		byte[] shared = agreement.generateSecret();
		byte[] prkKey = WebPushCrypto.hmac(authSecret, shared);
		byte[] info = ByteBuffer.allocate(14 + 65 + 65 + 1)
				.put("WebPush: info\0".getBytes(StandardCharsets.US_ASCII)).put(browserPublic).put(serverPublic).put((byte) 1).array();
		byte[] ikm = WebPushCrypto.hmac(prkKey, info);
		byte[] prk = WebPushCrypto.hmac(salt, ikm);
		byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, withOne("Content-Encoding: aes128gcm\0")), 16);
		byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, withOne("Content-Encoding: nonce\0")), 12);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		byte[] plain = cipher.doFinal(ciphertext);

		assertEquals(2, plain[plain.length - 1], "delimitador do último registro");
		assertArrayEquals(message, Arrays.copyOf(plain, plain.length - 1));
	}

	@Test
	void vapidHeaderIsASignedJwtForThePushServiceOrigin() throws Exception {
		KeyPair vapid = WebPushCrypto.generate();
		byte[] publicRaw = WebPushCrypto.rawPublic((ECPublicKey) vapid.getPublic());
		String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com/fcm/send/abc", "https://storyverse.example",
				1_900_000_000L, vapid.getPrivate(), publicRaw);

		assertTrue(header.startsWith("vapid t="));
		String jwt = header.substring(8, header.indexOf(", k="));
		assertEquals(WebPushCrypto.b64(publicRaw), header.substring(header.indexOf(", k=") + 4));
		String[] parts = jwt.split("\\.");
		String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""), claims);
		assertTrue(claims.contains("\"exp\":1900000000"));
		Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
		verify.initVerify(vapid.getPublic());
		verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
		assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
	}

	@Test
	void onlyBrowserPushServicesAreCalled() {
		assertTrue(PushService.allowedEndpoint("https://fcm.googleapis.com/fcm/send/abc"));
		assertTrue(PushService.allowedEndpoint("https://web.push.apple.com/QGx"));
		assertTrue(PushService.allowedEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x"));
		assertTrue(PushService.allowedEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x"));
		assertFalse(PushService.allowedEndpoint("http://fcm.googleapis.com/fcm/send/abc"), "sem https");
		assertFalse(PushService.allowedEndpoint("https://fcm.googleapis.com:8443/x"), "porta diferente");
		assertFalse(PushService.allowedEndpoint("https://169.254.169.254/latest/meta-data"), "rede interna");
		assertFalse(PushService.allowedEndpoint("https://evilfcm.googleapis.com.attacker.example/x"));
		assertFalse(PushService.allowedEndpoint("https://user@fcm.googleapis.com/x"));
	}

	private static byte[] withOne(String label) {
		byte[] text = label.getBytes(StandardCharsets.US_ASCII);
		byte[] out = Arrays.copyOf(text, text.length + 1);
		out[text.length] = 1;
		return out;
	}
}
