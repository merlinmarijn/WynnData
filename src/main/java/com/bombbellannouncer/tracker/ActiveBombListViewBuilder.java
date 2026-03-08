package com.bombbellannouncer.tracker;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombType;
import com.bombbellannouncer.subscription.ComboSubscription;
import com.bombbellannouncer.subscription.SubscriptionEvaluator;
import com.bombbellannouncer.subscription.SubscriptionMatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ActiveBombListViewBuilder {
	private final SubscriptionEvaluator subscriptionEvaluator = new SubscriptionEvaluator();

	public ActiveBombListView build(Collection<BombInfo> activeBombs, List<ComboSubscription> subscribedCombos, long nowMillis) {
		List<BombInfo> bombs = activeBombs.stream()
			.filter(bombInfo -> bombInfo.isActive(nowMillis))
			.toList();

		return new ActiveBombListView(
			buildBombSections(bombs, nowMillis),
			buildComboSections(bombs, subscribedCombos, nowMillis)
		);
	}

	private static List<ActiveBombListView.Section> buildBombSections(List<BombInfo> activeBombs, long nowMillis) {
		Map<BombType, List<BombInfo>> bombsByType = new EnumMap<>(BombType.class);
		for (BombInfo bombInfo : activeBombs) {
			bombsByType.computeIfAbsent(bombInfo.bombType(), ignored -> new ArrayList<>()).add(bombInfo);
		}

		List<ActiveBombListView.Section> sections = new ArrayList<>();
		for (BombType bombType : BombType.values()) {
			List<BombInfo> typeBombs = bombsByType.get(bombType);
			if (typeBombs == null || typeBombs.isEmpty()) {
				continue;
			}

			List<ActiveBombListView.Entry> entries = typeBombs.stream()
				.sorted(Comparator
					.comparingLong(BombInfo::startTimeMillis)
					.reversed()
					.thenComparing(BombInfo::server))
				.map(bombInfo -> new ActiveBombListView.Entry(bombInfo.server(), bombInfo.remainingMillis(nowMillis)))
				.toList();
			sections.add(new ActiveBombListView.Section(bombType.displayName(), entries));
		}
		return sections;
	}

	private List<ActiveBombListView.Section> buildComboSections(
		List<BombInfo> activeBombs,
		List<ComboSubscription> subscribedCombos,
		long nowMillis
	) {
		if (subscribedCombos.isEmpty()) {
			return List.of();
		}

		List<SubscriptionMatch> matches = subscriptionEvaluator.findMatches(activeBombs, List.of(), subscribedCombos, nowMillis);
		List<ActiveBombListView.Section> sections = new ArrayList<>(subscribedCombos.size());

		for (ComboSubscription combo : subscribedCombos) {
			List<ActiveBombListView.Entry> entries = matches.stream()
				.filter(match -> match.target().equals(combo))
				.sorted(Comparator
					.comparingLong(ActiveBombListViewBuilder::latestObservedAt)
					.reversed()
					.thenComparing(SubscriptionMatch::world))
				.map(match -> new ActiveBombListView.Entry(match.world(), match.remainingMillis(nowMillis)))
				.toList();
			sections.add(new ActiveBombListView.Section(combo.displayName(), entries));
		}

		return sections;
	}

	private static long latestObservedAt(SubscriptionMatch match) {
		return match.bombs().stream()
			.mapToLong(BombInfo::startTimeMillis)
			.max()
			.orElse(Long.MIN_VALUE);
	}
}
