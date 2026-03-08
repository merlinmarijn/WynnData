package com.bombbellannouncer.relay.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class CryptoUtilTest {
	@Test
	void verifiesDiscordStyleEd25519Signatures() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
		KeyPair keyPair = generator.generateKeyPair();
		byte[] rawPublicKey = stripX509Prefix(((EdECPublicKey) keyPair.getPublic()).getEncoded());

		String timestamp = "1700000000";
		byte[] body = "{\"type\":1}".getBytes(StandardCharsets.UTF_8);
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(keyPair.getPrivate());
		signature.update(timestamp.getBytes(StandardCharsets.UTF_8));
		signature.update(body);

		String signatureHex = HexFormat.of().formatHex(signature.sign());
		String publicKeyHex = HexFormat.of().formatHex(rawPublicKey);

		assertTrue(CryptoUtil.verifyDiscordSignature(publicKeyHex, timestamp, body, signatureHex));
		assertFalse(CryptoUtil.verifyDiscordSignature(publicKeyHex, timestamp, "{\"type\":2}".getBytes(StandardCharsets.UTF_8), signatureHex));
	}

	private static byte[] stripX509Prefix(byte[] encoded) {
		byte[] raw = new byte[32];
		System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
		return raw;
	}
}
