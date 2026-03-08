package com.bombbellannouncer.relay.service;

import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.AuthenticatedDevice;
import com.bombbellannouncer.relay.persistence.RelayStore.ContributorRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.EnrollmentGrant;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.bombbellannouncer.relay.util.CryptoUtil;

public final class EnrollmentService {
	private static final long ENROLLMENT_TTL_MILLIS = 10L * 60_000L;

	private final RelayStore store;
	private final DashboardSyncRequester syncRequester;

	public EnrollmentService(RelayStore store, DashboardSyncRequester syncRequester) {
		this.store = store;
		this.syncRequester = syncRequester;
	}

	public EnrollmentBundle issueEnrollmentBundle(String relayBaseUrl, ProjectRecord project, String discordUserId, String discordUsername, long nowMillis) {
		String oneTimeCode = CryptoUtil.randomToken(24);
		store.storeEnrollmentCode(
			project.projectId(),
			discordUserId,
			discordUsername,
			CryptoUtil.sha256Hex(oneTimeCode),
			nowMillis + ENROLLMENT_TTL_MILLIS,
			nowMillis
		);

		return new EnrollmentBundle(relayBaseUrl, project.projectId(), oneTimeCode, nowMillis + ENROLLMENT_TTL_MILLIS);
	}

	public EnrollmentRedeemResponse redeemEnrollment(EnrollmentRedeemRequest request, long nowMillis) {
		if (request == null || request.projectId() == null || request.projectId().isBlank() || request.oneTimeCode() == null || request.oneTimeCode().isBlank()) {
			return new EnrollmentRedeemResponse(false, "Invalid enrollment request.", "", "", "", "");
		}

		ProjectRecord project = store.findProjectById(request.projectId().trim());
		if (project == null) {
			return new EnrollmentRedeemResponse(false, "Unknown dashboard project.", "", "", "", "");
		}

		EnrollmentGrant grant = store.consumeEnrollmentCode(project.projectId(), CryptoUtil.sha256Hex(request.oneTimeCode().trim()), nowMillis);
		if (grant == null) {
			return new EnrollmentRedeemResponse(false, "Enrollment code is invalid or expired.", "", "", "", "");
		}

		ContributorRecord contributor = store.upsertContributor(project.projectId(), grant.discordUserId(), grant.discordUsername(), nowMillis);
		String contributorToken = CryptoUtil.randomToken(32);
		store.createDeviceCredential(
			contributor.contributorId(),
			CryptoUtil.sha256Hex(contributorToken),
			contributorToken.substring(0, Math.min(8, contributorToken.length())),
			nowMillis
		);

		return new EnrollmentRedeemResponse(
			true,
			"Enrollment succeeded.",
			contributorToken,
			grant.discordUsername(),
			project.dashboardName(),
			project.projectId()
		);
	}

	public ActionResult disconnectDevice(String projectId, String rawToken, long nowMillis) {
		AuthenticatedDevice device = authenticate(projectId, rawToken, nowMillis);
		if (device == null) {
			return new ActionResult(false, "Session token is invalid.");
		}

		store.revokeCredential(projectId, CryptoUtil.sha256Hex(rawToken), nowMillis);
		syncRequester.requestSync(projectId, false);
		return new ActionResult(true, "Contributor session revoked.");
	}

	public ActionResult revokeContributor(String projectId, String discordUserId, long nowMillis) {
		store.revokeCredentialsForDiscordUser(projectId, discordUserId, nowMillis);
		syncRequester.requestSync(projectId, false);
		return new ActionResult(true, "Contributor revoked.");
	}

	public AuthenticatedDevice authenticate(String projectId, String rawToken, long nowMillis) {
		if (projectId == null || projectId.isBlank() || rawToken == null || rawToken.isBlank()) {
			return null;
		}
		return store.authenticateDevice(projectId.trim(), CryptoUtil.sha256Hex(rawToken.trim()), nowMillis);
	}

	public record ActionResult(boolean success, String message) {
	}
}
