package com.bombbellannouncer.protocol;

import java.util.List;

public record BombSnapshotRequest(
	String clientVersion,
	String projectId,
	long observedAtMillis,
	long submitWindowSequence,
	List<BombSnapshotItem> bombs
) {
}
