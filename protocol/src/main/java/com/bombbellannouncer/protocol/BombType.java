package com.bombbellannouncer.protocol;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum BombType {
	COMBAT_XP("Combat XP", 20),
	DUNGEON("Dungeon", 10),
	LOOT("Loot", 20),
	PROFESSION_SPEED("Profession Speed", 10),
	PROFESSION_XP("Profession XP", 20),
	LOOT_CHEST("Loot Chest", 20);

	private static final Map<String, BombType> BY_NAME = buildLookup();

	private final String displayName;
	private final int activeMinutes;

	BombType(String displayName, int activeMinutes) {
		this.displayName = displayName;
		this.activeMinutes = activeMinutes;
	}

	public String displayName() {
		return displayName;
	}

	public int activeMinutes() {
		return activeMinutes;
	}

	public static Optional<BombType> fromName(String rawValue) {
		if (rawValue == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(BY_NAME.get(rawValue.trim().toUpperCase(Locale.ROOT)));
	}

	private static Map<String, BombType> buildLookup() {
		Map<String, BombType> lookup = new LinkedHashMap<>();
		for (BombType bombType : values()) {
			lookup.put(bombType.name(), bombType);
		}
		return lookup;
	}
}
