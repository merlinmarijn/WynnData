package com.bombbellannouncer.protocol;

public record ReporterSubmitIntentRequest(
	String clientVersion,
	String projectId,
	long observedAtMillis,
	String snapshotHash
) {
}
