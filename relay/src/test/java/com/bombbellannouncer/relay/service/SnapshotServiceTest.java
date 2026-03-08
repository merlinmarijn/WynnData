package com.bombbellannouncer.relay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bombbellannouncer.protocol.BombBellProofRequest;
import com.bombbellannouncer.protocol.BombSnapshotRequest;
import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.protocol.ReporterStatusRequest;
import com.bombbellannouncer.protocol.ReporterSubmitIntentRequest;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SnapshotServiceTest {
	@Test
	void outOfTurnReporterCannotSubmitSnapshots(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);
		EnrollmentService enrollmentService = new EnrollmentService(store, (projectId, force) -> {
		});
		ReporterCoordinationService coordinationService = new ReporterCoordinationService(store, (projectId, force) -> {
		}, LoggerFactory.getLogger("test"));
		SnapshotService snapshotService = new SnapshotService(store, enrollmentService, (projectId, force) -> {
		}, coordinationService);

		String primaryToken = enroll(enrollmentService, project, "user-1", "Primary", 1_000L);
		String secondaryToken = enroll(enrollmentService, project, "user-2", "Secondary", 2_000L);

		makeEligible(coordinationService, primaryToken, project, 1_100L, "LOOT|WC1|primary");
		makeEligible(coordinationService, secondaryToken, project, 2_100L, "LOOT|WC1|secondary");

		coordinationService.handleSubmitIntent(
			secondaryToken,
			new ReporterSubmitIntentRequest("1.0.0", project.projectId(), 3_000L, "state-one"),
			3_000L
		);

		SnapshotService.ActionResult result = snapshotService.ingestSnapshot(
			secondaryToken,
			new BombSnapshotRequest("1.0.0", project.projectId(), 3_100L, 1L, List.of()),
			3_100L
		);

		assertEquals(409, result.statusCode());
		assertEquals(ReporterRole.SECONDARY, result.response().role());
	}

	private static void makeEligible(
		ReporterCoordinationService coordinationService,
		String token,
		ProjectRecord project,
		long nowMillis,
		String notificationKey
	) {
		coordinationService.handleStatus(token, new ReporterStatusRequest("1.0.0", project.projectId(), nowMillis), nowMillis);
		coordinationService.handleBombBellProof(token, new BombBellProofRequest("1.0.0", project.projectId(), nowMillis + 50L, notificationKey), nowMillis + 50L);
	}

	private static String enroll(EnrollmentService enrollmentService, ProjectRecord project, String discordUserId, String username, long nowMillis) {
		EnrollmentBundle bundle = enrollmentService.issueEnrollmentBundle("https://relay.example", project, discordUserId, username, nowMillis);
		EnrollmentRedeemResponse response = enrollmentService.redeemEnrollment(
			new EnrollmentRedeemRequest(project.projectId(), bundle.oneTimeCode(), "1.0.0"),
			nowMillis + 100L
		);
		return response.contributorToken();
	}
}
