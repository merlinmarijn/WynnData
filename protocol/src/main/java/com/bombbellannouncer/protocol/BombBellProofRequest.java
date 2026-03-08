package com.bombbellannouncer.protocol;

public record BombBellProofRequest(
	String clientVersion,
	String projectId,
	long observedAtMillis,
	String notificationKey
) {
}
