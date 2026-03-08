package com.bombbellannouncer.bomb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BombMessageParserTest {
	private final BombMessageParser parser = new BombMessageParser();

	@Test
	void normalizesBombTypeAliases() {
		assertEquals(BombType.COMBAT_XP, BombType.fromString("Combat Experience").orElseThrow());
		assertEquals(BombType.DUNGEON, BombType.fromString("Free Dungeon Entry").orElseThrow());
		assertEquals(BombType.LOOT_CHEST, BombType.fromString("More Chest Loot").orElseThrow());
	}

	@Test
	void parsesBombBellMessages() {
		BombMessageParser.BombBellMatch match = parser.parseBombBell(
			"\uE01E ExamplePlayer has thrown a Combat XP Bomb on wc32"
		).orElseThrow();

		assertEquals("ExamplePlayer", match.user());
		assertEquals(BombType.COMBAT_XP, match.bombType());
		assertEquals("WC32", match.server());
	}

	@Test
	void parsesBombExpirationMessages() {
		BombType bombType = parser.parseBombExpiration("Loot Chest Bomb has expired!").orElseThrow();
		assertEquals(BombType.LOOT_CHEST, bombType);
	}

	@Test
	void parsesLocalBombThrownMessages() {
		BombType bombType = parser.parseLocalBombThrown("\uE001 Profession Experience Bomb").orElseThrow();
		assertEquals(BombType.PROFESSION_XP, bombType);
	}

	@Test
	void parsesBossBarMessagesWithMinutesAndSeconds() {
		BombMessageParser.BossBarMatch minuteMatch = parser.parseBossBar(
			"Double Loot Chest from ExamplePlayer [9m]"
		).orElseThrow();
		BombMessageParser.BossBarMatch secondMatch = parser.parseBossBar(
			"Profession Speed from ExamplePlayer [45s]"
		).orElseThrow();

		assertEquals(BombType.LOOT_CHEST, minuteMatch.bombType());
		assertEquals(9.5f, minuteMatch.lengthMinutes());
		assertEquals(BombType.PROFESSION_SPEED, secondMatch.bombType());
		assertEquals(0.75f, secondMatch.lengthMinutes());
	}

	@Test
	void parsesCurrentWorldNameFromTabListEntry() {
		String worldName = parser.parseCurrentWorldName("  Global [WC17] ").orElseThrow();
		assertEquals("WC17", worldName);
	}

	@Test
	void ignoresUnrelatedMessages() {
		assertTrue(parser.parseBombBell("hello world").isEmpty());
		assertTrue(parser.parseLocalBombThrown("hello world").isEmpty());
		assertTrue(parser.parseBombExpiration("nothing here").isEmpty());
		assertTrue(parser.parseBossBar("random title").isEmpty());
		assertFalse(parser.normalizePlainText(null).length() > 0);
	}
}
