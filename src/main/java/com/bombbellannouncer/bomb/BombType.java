package com.bombbellannouncer.bomb;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum BombType {
	COMBAT_XP("Combat XP", 20, "Combat XP", "Combat Experience"),
	DUNGEON("Dungeon", 10, "Dungeon", "Free Dungeon Entry"),
	LOOT("Loot", 20, "Loot"),
	PROFESSION_SPEED("Profession Speed", 10, "Profession Speed"),
	PROFESSION_XP("Profession XP", 20, "Profession XP", "Profession Experience"),
	LOOT_CHEST("Loot Chest", 20, "Loot Chest", "More Chest Loot");

	private static final Map<String, BombType> BY_ALIAS = buildAliasLookup();

	private final String displayName;
	private final int activeMinutes;
	private final String[] aliases;

	BombType(String displayName, int activeMinutes, String... aliases) {
		this.displayName = displayName;
		this.activeMinutes = activeMinutes;
		this.aliases = aliases;
	}

	public String displayName() {
		return displayName;
	}

	public int activeMinutes() {
		return activeMinutes;
	}

	public static Optional<BombType> fromString(String rawValue) {
		if (rawValue == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(BY_ALIAS.get(normalizeAlias(rawValue)));
	}

	private static Map<String, BombType> buildAliasLookup() {
		Map<String, BombType> lookup = new LinkedHashMap<>();

		for (BombType bombType : values()) {
			for (String alias : bombType.aliases) {
				lookup.put(normalizeAlias(alias), bombType);
			}
		}

		return lookup;
	}

	private static String normalizeAlias(String rawValue) {
		return rawValue.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}
}
