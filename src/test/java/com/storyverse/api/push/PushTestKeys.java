package com.storyverse.api.push;

import java.security.interfaces.ECPublicKey;

/** Chaves de navegador de mentira para os testes de outros pacotes (a criptografia é interna). */
public final class PushTestKeys {
	private PushTestKeys() {
	}

	public static String b64(byte[] bytes) {
		return WebPushCrypto.b64(bytes);
	}

	public static byte[] unb64(String value) {
		return WebPushCrypto.unb64(value);
	}

	public static String browserPublicKey() {
		return WebPushCrypto.b64(WebPushCrypto.rawPublic((ECPublicKey) WebPushCrypto.generate().getPublic()));
	}
}
