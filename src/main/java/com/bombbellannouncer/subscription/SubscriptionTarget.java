package com.bombbellannouncer.subscription;

public sealed interface SubscriptionTarget permits BombSubscription, ComboSubscription {
	String key();

	String displayName();
}
