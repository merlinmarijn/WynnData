package com.bombbellannouncer.protocol;

public record ReporterStatusRequest(
	String clientVersion,
	String projectId,
	long observedAtMillis
) {
}
