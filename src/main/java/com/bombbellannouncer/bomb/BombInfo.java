package com.bombbellannouncer.bomb;

import java.util.Locale;
import java.util.Objects;

public record BombInfo(
	String user,
	BombType bombType,
	String server,
	long startTimeMillis,
	float lengthMinutes,
	BombSource source
) {
	public BombInfo {
		Objects.requireNonNull(user, "user");
		Objects.requireNonNull(bombType, "bombType");
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(source, "source");

		user = normalizeUser(user);
		server = BombKey.normalizeServer(server);
	}

	public long durationMillis() {
		return Math.round(lengthMinutes * 60_000.0f);
	}

	public long expiresAtMillis() {
		return startTimeMillis + durationMillis();
	}

	public long remainingMillis(long nowMillis) {
		return Math.max(0L, expiresAtMillis() - nowMillis);
	}

	public boolean isActive(long nowMillis) {
		return nowMillis < expiresAtMillis();
	}

	private static String normalizeUser(String value) {
		String normalized = value.trim().replaceAll("\\s+", " ");
		return normalized.isEmpty() ? "Unknown" : normalized;
	}

	public String durationMinutesString() {
		if (lengthMinutes == Math.rint(lengthMinutes)) {
			return Integer.toString((int) lengthMinutes);
		}

		return String.format(Locale.ROOT, "%.1f", lengthMinutes);
	}
}
