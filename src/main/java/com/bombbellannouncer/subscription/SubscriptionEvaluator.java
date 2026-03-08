package com.bombbellannouncer.subscription;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SubscriptionEvaluator {
	public List<SubscriptionMatch> findMatches(
		Collection<BombInfo> activeBombs,
		Collection<BombSubscription> bombSubscriptions,
		Collection<ComboSubscription> comboSubscriptions,
		long nowMillis
	) {
		Map<String, Map<BombType, BombInfo>> bombsByWorld = buildBombsByWorld(activeBombs, nowMillis);
		List<SubscriptionMatch> matches = new ArrayList<>();

		for (BombSubscription subscription : bombSubscriptions) {
			for (Map.Entry<String, Map<BombType, BombInfo>> entry : bombsByWorld.entrySet()) {
				BombInfo bombInfo = entry.getValue().get(subscription.bombType());
				if (bombInfo != null) {
					matches.add(new SubscriptionMatch(subscription, entry.getKey(), List.of(bombInfo), bombInfo.expiresAtMillis()));
				}
			}
		}

		for (ComboSubscription subscription : comboSubscriptions) {
			for (Map.Entry<String, Map<BombType, BombInfo>> entry : bombsByWorld.entrySet()) {
				List<BombInfo> matchedBombs = new ArrayList<>();
				long effectiveExpiresAt = Long.MAX_VALUE;
				boolean allPresent = true;

				for (BombType bombType : subscription.bombTypes()) {
					BombInfo bombInfo = entry.getValue().get(bombType);
					if (bombInfo == null) {
						allPresent = false;
						break;
					}

					matchedBombs.add(bombInfo);
					effectiveExpiresAt = Math.min(effectiveExpiresAt, bombInfo.expiresAtMillis());
				}

				if (allPresent) {
					matches.add(new SubscriptionMatch(subscription, entry.getKey(), matchedBombs, effectiveExpiresAt));
				}
			}
		}

		matches.sort(Comparator
			.comparing((SubscriptionMatch match) -> match.target().displayName())
			.thenComparing(SubscriptionMatch::world)
			.thenComparingLong(SubscriptionMatch::effectiveExpiresAtMillis));
		return matches;
	}

	public List<SubscriptionMatch> findMatchesForTarget(Collection<BombInfo> activeBombs, SubscriptionTarget target, long nowMillis) {
		if (target instanceof BombSubscription bombSubscription) {
			return findMatches(activeBombs, List.of(bombSubscription), List.of(), nowMillis);
		}
		if (target instanceof ComboSubscription comboSubscription) {
			return findMatches(activeBombs, List.of(), List.of(comboSubscription), nowMillis);
		}
		return List.of();
	}

	private static Map<String, Map<BombType, BombInfo>> buildBombsByWorld(Collection<BombInfo> activeBombs, long nowMillis) {
		Map<String, Map<BombType, BombInfo>> bombsByWorld = new HashMap<>();
		for (BombInfo bombInfo : activeBombs) {
			if (!bombInfo.isActive(nowMillis)) {
				continue;
			}

			bombsByWorld.computeIfAbsent(bombInfo.server(), ignored -> new EnumMap<>(BombType.class))
				.put(bombInfo.bombType(), bombInfo);
		}
		return bombsByWorld;
	}
}
