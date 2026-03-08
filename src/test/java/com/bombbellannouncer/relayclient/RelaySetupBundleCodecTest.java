package com.bombbellannouncer.relayclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class RelaySetupBundleCodecTest {
	private static final Gson GSON = new Gson();

	@Test
	void decodesValidSetupBundles() {
		EnrollmentBundle decoded = RelaySetupBundleCodec.decode(encode(new EnrollmentBundle(
			"https://relay.example/",
			"project-1",
			"one-time-code",
			9_999_999L
		)), 1_000L);

		assertEquals("https://relay.example", decoded.relayBaseUrl());
		assertEquals("project-1", decoded.projectId());
		assertEquals("one-time-code", decoded.oneTimeCode());
	}

	@Test
	void rejectsExpiredBundles() {
		assertThrows(IllegalArgumentException.class, () -> RelaySetupBundleCodec.decode(
			encode(new EnrollmentBundle("https://relay.example", "project-1", "one-time-code", 1_000L)),
			1_001L
		));
	}

	private static String encode(EnrollmentBundle bundle) {
		String json = GSON.toJson(bundle);
		return "bba1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
