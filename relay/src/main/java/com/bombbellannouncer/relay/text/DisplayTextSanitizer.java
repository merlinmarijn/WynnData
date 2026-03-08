package com.bombbellannouncer.relay.text;

public final class DisplayTextSanitizer {
	private DisplayTextSanitizer() {
	}

	public static String sanitizeInline(String value) {
		if (value == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(value.length());
		value.codePoints()
			.filter(DisplayTextSanitizer::isAllowedCodePoint)
			.forEach(codePoint -> builder.appendCodePoint(codePoint));

		return builder.toString().trim().replaceAll("\\s+", " ");
	}

	public static String sanitizeName(String value, String fallback) {
		String sanitized = sanitizeInline(value);
		return sanitized.isBlank() ? fallback : sanitized;
	}

	private static boolean isAllowedCodePoint(int codePoint) {
		if (Character.isISOControl(codePoint)) {
			return false;
		}

		int type = Character.getType(codePoint);
		if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE || type == Character.UNASSIGNED) {
			return false;
		}

		return !Character.isIdentifierIgnorable(codePoint);
	}
}
