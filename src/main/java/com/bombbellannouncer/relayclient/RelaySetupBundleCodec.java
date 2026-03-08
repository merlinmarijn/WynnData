package com.bombbellannouncer.relayclient;

import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RelaySetupBundleCodec {
	private static final Gson GSON = new Gson();
	private static final String PREFIX = "bba1:";

	private RelaySetupBundleCodec() {
	}

	public static EnrollmentBundle decode(String rawValue, long nowMillis) {
		if (rawValue == null || rawValue.isBlank()) {
			throw new IllegalArgumentException("Paste the setup bundle from /bombbell enroll.");
		}

		String trimmed = rawValue.trim();
		if (!trimmed.startsWith(PREFIX)) {
			throw new IllegalArgumentException("Invalid setup bundle format.");
		}

		try {
			String encoded = trimmed.substring(PREFIX.length());
			byte[] decoded = Base64.getUrlDecoder().decode(encoded);
			EnrollmentBundle bundle = GSON.fromJson(new String(decoded, StandardCharsets.UTF_8), EnrollmentBundle.class);
			if (bundle == null || bundle.relayBaseUrl() == null || bundle.projectId() == null || bundle.oneTimeCode() == null) {
				throw new IllegalArgumentException("Invalid setup bundle payload.");
			}
			if (bundle.expiresAtMillis() <= nowMillis) {
				throw new IllegalArgumentException("This setup bundle has expired.");
			}
			return new EnrollmentBundle(
				normalizeBaseUrl(bundle.relayBaseUrl()),
				bundle.projectId().trim(),
				bundle.oneTimeCode().trim(),
				bundle.expiresAtMillis()
			);
		} catch (IllegalArgumentException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalArgumentException("Invalid setup bundle payload.", exception);
		}
	}

	private static String normalizeBaseUrl(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.endsWith("/")) {
			return normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}
}
