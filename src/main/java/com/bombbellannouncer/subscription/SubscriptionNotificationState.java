package com.bombbellannouncer.subscription;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SubscriptionNotificationState {
	private final Set<String> activeNotificationKeys = new HashSet<>();

	public synchronized List<SubscriptionMatch> collectNewMatches(List<SubscriptionMatch> currentMatches) {
		Set<String> currentKeys = currentMatches.stream().map(SubscriptionMatch::notificationKey).collect(java.util.stream.Collectors.toSet());
		List<SubscriptionMatch> newMatches = currentMatches.stream()
			.filter(match -> !activeNotificationKeys.contains(match.notificationKey()))
			.toList();
		activeNotificationKeys.retainAll(currentKeys);
		activeNotificationKeys.addAll(currentKeys);
		return newMatches;
	}

	public synchronized void clearAll() {
		activeNotificationKeys.clear();
	}

	public synchronized void clearTarget(SubscriptionTarget target) {
		String prefix = target.key() + "|";
		activeNotificationKeys.removeIf(key -> key.startsWith(prefix));
	}
}
