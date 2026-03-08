package com.bombbellannouncer.protocol;

import java.util.Locale;
import java.util.Optional;

public enum ReporterRole {
	PRIMARY("Primary reporter"),
	SECONDARY("Secondary reporter"),
	TERTIARY("Tertiary reporter"),
	WAITING("Waiting reporter"),
	INELIGIBLE("Waiting for first bomb bell");

	private final String displayName;

	ReporterRole(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	public boolean eligible() {
		return this != INELIGIBLE;
	}

	public boolean assignedToSubmitChain() {
		return this == PRIMARY || this == SECONDARY || this == TERTIARY;
	}

	public static Optional<ReporterRole> fromName(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return Optional.empty();
		}

		String normalized = rawValue.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
		if ("ACTIVE".equals(normalized)) {
			return Optional.of(PRIMARY);
		}
		if ("STANDBY".equals(normalized)) {
			return Optional.of(WAITING);
		}
		for (ReporterRole role : values()) {
			if (role.name().equals(normalized)) {
				return Optional.of(role);
			}
		}

		return Optional.empty();
	}
}
