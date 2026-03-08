package com.bombbellannouncer.protocol;

public record BombSnapshotItem(
	BombType bombType,
	String server,
	String user,
	long startTimeMillis,
	long expiresAtMillis,
	BombSource source
) {
}
