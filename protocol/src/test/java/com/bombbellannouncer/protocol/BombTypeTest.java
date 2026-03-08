package com.bombbellannouncer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BombTypeTest {
	@Test
	void resolvesBombTypeNames() {
		assertEquals(BombType.PROFESSION_XP, BombType.fromName("PROFESSION_XP").orElseThrow());
	}
}
