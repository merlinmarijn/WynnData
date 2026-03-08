package com.bombbellannouncer.relay.discord;

import java.util.Locale;
import java.util.Optional;

public enum DashboardComboSortMode {
	WORLD,
	EARLIEST_EXPIRY,
	LATEST_EXPIRY;

	public static Optional<DashboardComboSortMode> fromName(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return Optional.empty();
		}

		String normalized = rawValue.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
		for (DashboardComboSortMode value : values()) {
			if (value.name().equals(normalized)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
