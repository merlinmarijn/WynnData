package com.bombbellannouncer.subscription;

import com.bombbellannouncer.bomb.BombType;
import java.util.Objects;

public record BombSubscription(BombType bombType) implements SubscriptionTarget {
	public BombSubscription {
		Objects.requireNonNull(bombType, "bombType");
	}

	@Override
	public String key() {
		return "bomb:" + bombType.name();
	}

	@Override
	public String displayName() {
		return bombType.displayName();
	}
}
