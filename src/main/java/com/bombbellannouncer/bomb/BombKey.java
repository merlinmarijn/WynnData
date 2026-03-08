package com.bombbellannouncer.bomb;

import java.util.Locale;
import java.util.Objects;

public record BombKey(String server, BombType bombType) {
	public BombKey {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(bombType, "bombType");

		server = normalizeServer(server);
	}

	public static String normalizeServer(String value) {
		return value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}
}
