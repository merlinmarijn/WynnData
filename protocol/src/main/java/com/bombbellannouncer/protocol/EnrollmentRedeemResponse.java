package com.bombbellannouncer.protocol;

public record EnrollmentRedeemResponse(
	boolean success,
	String message,
	String contributorToken,
	String linkedDiscordUser,
	String dashboardName,
	String projectId
) {
}
