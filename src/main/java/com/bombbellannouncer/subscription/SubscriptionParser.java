package com.bombbellannouncer.subscription;

import com.bombbellannouncer.bomb.BombType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SubscriptionParser {
	private SubscriptionParser() {
	}

	public static BombType parseBombType(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			throw new IllegalArgumentException("Choose a valid bomb type.");
		}

		return BombType.fromString(rawValue)
			.or(() -> parseEnumBombType(rawValue))
			.orElseThrow(() -> new IllegalArgumentException("Choose a valid bomb type."));
	}

	public static ComboSubscription parseCombo(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			throw new IllegalArgumentException("Provide a comma-separated bomb type list.");
		}

		List<BombType> bombTypes = new ArrayList<>();
		for (String token : rawValue.split(",")) {
			bombTypes.add(parseBombType(token));
		}

		try {
			return new ComboSubscription(bombTypes);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("A combo needs at least 2 unique bomb types.");
		}
	}

	public static ComboSubscription parseEncodedCombo(String rawValue) {
		return parseCombo(rawValue == null ? "" : rawValue.replace('_', ' '));
	}

	private static java.util.Optional<BombType> parseEnumBombType(String rawValue) {
		String normalized = rawValue.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
		try {
			return java.util.Optional.of(BombType.valueOf(normalized));
		} catch (IllegalArgumentException exception) {
			return java.util.Optional.empty();
		}
	}
}
