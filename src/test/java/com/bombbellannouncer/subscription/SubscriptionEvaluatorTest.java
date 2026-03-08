package com.bombbellannouncer.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombSource;
import com.bombbellannouncer.bomb.BombType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SubscriptionEvaluatorTest {
	private final SubscriptionEvaluator evaluator = new SubscriptionEvaluator();

	@Test
	void findsBombAndComboMatchesByWorld() {
		long nowMillis = 10_000L;
		List<BombInfo> activeBombs = List.of(
			new BombInfo("ToNoize", BombType.PROFESSION_SPEED, "EU19", nowMillis - 5_000L, 10.0f, BombSource.CHAT_BELL),
			new BombInfo("TheBeamXD", BombType.PROFESSION_XP, "EU19", nowMillis - 5_000L, 20.0f, BombSource.CHAT_BELL),
			new BombInfo("SomeoneElse", BombType.LOOT, "EU20", nowMillis - 5_000L, 20.0f, BombSource.CHAT_BELL)
		);

		List<SubscriptionMatch> matches = evaluator.findMatches(
			activeBombs,
			List.of(new BombSubscription(BombType.LOOT)),
			List.of(new ComboSubscription(List.of(BombType.PROFESSION_XP, BombType.PROFESSION_SPEED))),
			nowMillis
		);

		assertEquals(2, matches.size());
		assertEquals("EU20", matches.getFirst().world());
		assertEquals("EU19", matches.get(1).world());
		assertEquals(605_000L, matches.get(1).effectiveExpiresAtMillis());
	}

	@Test
	void notificationStateDedupesUntilMatchDisappears() {
		long nowMillis = 1_000L;
		SubscriptionNotificationState state = new SubscriptionNotificationState();
		SubscriptionMatch match = evaluator.findMatches(
			List.of(new BombInfo("ExamplePlayer", BombType.LOOT, "EU27", nowMillis, 20.0f, BombSource.CHAT_BELL)),
			List.of(new BombSubscription(BombType.LOOT)),
			List.of(),
			nowMillis
		).getFirst();

		assertEquals(1, state.collectNewMatches(List.of(match)).size());
		assertEquals(0, state.collectNewMatches(List.of(match)).size());
		assertEquals(0, state.collectNewMatches(List.of()).size());
		assertFalse(state.collectNewMatches(List.of(match)).isEmpty());
	}
}
