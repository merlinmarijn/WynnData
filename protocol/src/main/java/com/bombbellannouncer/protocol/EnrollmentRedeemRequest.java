package com.bombbellannouncer.protocol;

public record EnrollmentRedeemRequest(
	String projectId,
	String oneTimeCode,
	String clientVersion
) {
}
