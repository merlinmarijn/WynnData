package com.bombbellannouncer.protocol;

public record EnrollmentBundle(
	String relayBaseUrl,
	String projectId,
	String oneTimeCode,
	long expiresAtMillis
) {
}
