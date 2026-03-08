package com.bombbellannouncer.tracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombSource;
import com.bombbellannouncer.bomb.BombType;
import com.bombbellannouncer.subscription.ComboSubscription;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ActiveBombListViewBuilderTest {
	private final ActiveBombListViewBuilder builder = new ActiveBombListViewBuilder();

	@Test
	void groupsRawBombsByTypeAndSortsNewestFirst() {
		long nowMillis = 50_000L;
		ActiveBombListView view = builder.build(
			List.of(
				new BombInfo("Older", BombType.LOOT, "EU27", 10_000L, 20.0f, BombSource.CHAT_BELL),
				new BombInfo("Newer", BombType.LOOT, "EU19", 20_000L, 20.0f, BombSource.CHAT_BELL),
				new BombInfo("Speed", BombType.PROFESSION_SPEED, "NA10", 15_000L, 10.0f, BombSource.CHAT_BELL)
			),
			List.of(),
			nowMillis
		);

		assertEquals(List.of("Loot", "Profession Speed"), view.bombSections().stream().map(ActiveBombListView.Section::title).toList());
		assertEquals(List.of("EU19", "EU27"), view.bombSections().getFirst().entries().stream().map(ActiveBombListView.Entry::world).toList());
		assertTrue(view.comboSections().isEmpty());
	}

	@Test
	void includesSubscribedCombosAndShowsEmptyOnes() {
		long nowMillis = 30_000L;
		ComboSubscription matchingCombo = new ComboSubscription(List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP));
		ComboSubscription emptyCombo = new ComboSubscription(List.of(BombType.LOOT, BombType.LOOT_CHEST));
		ActiveBombListView view = builder.build(
			List.of(
				new BombInfo("Speed", BombType.PROFESSION_SPEED, "EU27", 10_000L, 10.0f, BombSource.CHAT_BELL),
				new BombInfo("Xp", BombType.PROFESSION_XP, "EU27", 5_000L, 20.0f, BombSource.CHAT_BELL)
			),
			List.of(matchingCombo, emptyCombo),
			nowMillis
		);

		assertEquals(List.of("Profession Speed + Profession XP", "Loot + Loot Chest"), view.comboSections().stream().map(ActiveBombListView.Section::title).toList());
		assertEquals(List.of("EU27"), view.comboSections().getFirst().entries().stream().map(ActiveBombListView.Entry::world).toList());
		assertTrue(view.comboSections().get(1).entries().isEmpty());
		assertEquals(580_000L, view.comboSections().getFirst().entries().getFirst().remainingMillis());
	}
}
