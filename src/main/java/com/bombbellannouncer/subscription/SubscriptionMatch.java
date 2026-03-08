package com.bombbellannouncer.subscription;

import com.bombbellannouncer.bomb.BombInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record SubscriptionMatch(
	SubscriptionTarget target,
	String world,
	List<BombInfo> bombs,
	long effectiveExpiresAtMillis
) {
	public SubscriptionMatch {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(bombs, "bombs");
		bombs = bombs.stream()
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(BombInfo::bombType))
			.toList();
		if (bombs.isEmpty()) {
			throw new IllegalArgumentException("Subscription match requires at least one bomb.");
		}
	}

	public String notificationKey() {
		return target.key() + "|" + world + "|" + effectiveExpiresAtMillis;
	}

	public long remainingMillis(long nowMillis) {
		return Math.max(0L, effectiveExpiresAtMillis - nowMillis);
	}

	public String lobbyCode() {
		return world.toLowerCase(Locale.ROOT);
	}
}
