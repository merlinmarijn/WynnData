package com.bombbellannouncer.relay.util;

import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class CryptoUtil {
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final byte[] DISCORD_PUBLIC_KEY_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	private CryptoUtil() {
	}

	public static String randomToken(int byteCount) {
		byte[] bytes = new byte[byteCount];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String sha256Hex(String rawValue) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(rawValue.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (Exception exception) {
			throw new IllegalStateException("Missing SHA-256 support", exception);
		}
	}

	public static boolean verifyDiscordSignature(String publicKeyHex, String timestamp, byte[] body, String signatureHex) {
		try {
			byte[] publicKey = HexFormat.of().parseHex(publicKeyHex);
			byte[] x509Bytes = new byte[DISCORD_PUBLIC_KEY_PREFIX.length + publicKey.length];
			System.arraycopy(DISCORD_PUBLIC_KEY_PREFIX, 0, x509Bytes, 0, DISCORD_PUBLIC_KEY_PREFIX.length);
			System.arraycopy(publicKey, 0, x509Bytes, DISCORD_PUBLIC_KEY_PREFIX.length, publicKey.length);

			byte[] signatureBytes = HexFormat.of().parseHex(signatureHex);
			KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
			Signature signature = Signature.getInstance("Ed25519");
			signature.initVerify(keyFactory.generatePublic(new X509EncodedKeySpec(x509Bytes)));
			signature.update(timestamp.getBytes(StandardCharsets.UTF_8));
			signature.update(body);
			return signature.verify(signatureBytes);
		} catch (Exception exception) {
			return false;
		}
	}

	public static String encodeSetupBundle(EnrollmentBundle bundle) {
		String json = GSON.toJson(bundle);
		String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		return "bba1:" + encoded;
	}
}
