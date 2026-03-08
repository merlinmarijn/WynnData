package com.bombbellannouncer.tracker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RecentNotificationCacheTest {
	@Test
	void suppressesDuplicateNotificationsWithinTtl() {
		RecentNotificationCache cache = new RecentNotificationCache(5_000L);

		assertTrue(cache.shouldSend("combat|wc1|player", 1_000L));
		assertFalse(cache.shouldSend("combat|wc1|player", 5_000L));
		assertTrue(cache.shouldSend("combat|wc1|player", 6_001L));
	}
}
