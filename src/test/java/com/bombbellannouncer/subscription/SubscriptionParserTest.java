package com.bombbellannouncer.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bombbellannouncer.bomb.BombType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SubscriptionParserTest {
	@Test
	void parsesBombAliasesAndEnumNames() {
		assertEquals(BombType.PROFESSION_XP, SubscriptionParser.parseBombType("Profession Experience"));
		assertEquals(BombType.PROFESSION_SPEED, SubscriptionParser.parseBombType("PROFESSION_SPEED"));
	}

	@Test
	void normalizesComboSubscriptions() {
		ComboSubscription comboSubscription = SubscriptionParser.parseCombo("Profession XP, Profession Speed, Profession XP");
		assertEquals(List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), comboSubscription.bombTypes());
	}

	@Test
	void rejectsInvalidCombos() {
		assertThrows(IllegalArgumentException.class, () -> SubscriptionParser.parseCombo("Loot"));
		assertThrows(IllegalArgumentException.class, () -> SubscriptionParser.parseBombType("not-a-bomb"));
	}
}
