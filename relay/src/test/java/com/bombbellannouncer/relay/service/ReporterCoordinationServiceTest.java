package com.bombbellannouncer.relay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bombbellannouncer.protocol.BombBellProofRequest;
import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.protocol.ReporterStatusRequest;
import com.bombbellannouncer.protocol.ReporterSubmitIntentRequest;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterChainSlotRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.SubmitWindowRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class ReporterCoordinationServiceTest {
	@Test
	void firstProvenDeviceBecomesPrimaryReporter(@TempDir Path tempDir) {
		TestContext context = new TestContext(tempDir, 2);

		context.status(0, 1_100L);
		ReporterCoordinationService.ActionResult result = context.proof(0, 1_200L, "COMBAT_XP|WC1|player");

		assertEquals(ReporterRole.PRIMARY, result.response().role());
		List<ReporterChainSlotRecord> chain = context.store.findReporterChain(context.project.projectId());
		assertEquals(1, chain.size());
		assertEquals(context.credentialId(0), chain.getFirst().credentialId());
		assertEquals(ReporterRole.PRIMARY, chain.getFirst().role());
	}

	@Test
	void onlyThreeEligibleDevicesJoinTheSubmitChain(@TempDir Path tempDir) {
		TestContext context = new TestContext(tempDir, 4);

		for (int index = 0; index < 4; index++) {
			context.status(index, 1_000L + (index * 100L));
			context.proof(index, 1_050L + (index * 100L), "LOOT|WC" + index + "|user-" + index);
		}

		List<ReporterChainSlotRecord> chain = context.store.findReporterChain(context.project.projectId());
		assertEquals(3, chain.size());
		assertEquals(List.of(ReporterRole.PRIMARY, ReporterRole.SECONDARY, ReporterRole.TERTIARY), chain.stream().map(ReporterChainSlotRecord::role).toList());
		assertEquals(ReporterRole.WAITING, context.status(3, 2_000L).response().role());
	}

	@Test
	void submitWindowFailsOverToSecondaryAfterPrimaryMiss(@TempDir Path tempDir) {
		TestContext context = new TestContext(tempDir, 2);
		context.makeEligible(0, 1_100L);
		context.makeEligible(1, 1_200L);

		ReporterCoordinationService.SubmitIntentActionResult submitIntent = context.intent(1, 2_000L, "state-one");
		assertEquals(ReporterRole.SECONDARY, submitIntent.response().role());
		assertEquals(1L, submitIntent.response().submitWindowSequence());

		context.coordinationService.runHousekeepingOnce(12_500L);

		SubmitWindowRecord submitWindow = context.store.findSubmitWindow(context.project.projectId());
		assertNotNull(submitWindow);
		assertEquals(context.credentialId(1), submitWindow.allowedCredentialId());
		assertEquals(ReporterRole.SECONDARY, context.status(1, 12_600L).response().role());
		assertEquals(1, context.store.findReporterChain(context.project.projectId()).stream()
			.filter(slot -> slot.credentialId() == context.credentialId(0))
			.findFirst()
			.orElseThrow()
			.missCount());
	}

	@Test
	void primaryReporterIsDemotedAfterTwoMissedWindows(@TempDir Path tempDir) {
		TestContext context = new TestContext(tempDir, 4);
		for (int index = 0; index < 4; index++) {
			context.makeEligible(index, 1_000L + (index * 100L));
		}

		context.intent(3, 5_000L, "state-one");
		context.coordinationService.runHousekeepingOnce(16_000L);
		context.coordinationService.runHousekeepingOnce(27_000L);
		context.coordinationService.runHousekeepingOnce(38_000L);

		context.intent(3, 45_000L, "state-two");
		context.coordinationService.runHousekeepingOnce(56_000L);

		List<ReporterChainSlotRecord> chain = context.store.findReporterChain(context.project.projectId());
		assertEquals(context.credentialId(1), chain.get(0).credentialId());
		assertEquals(ReporterRole.PRIMARY, chain.get(0).role());
		assertEquals(context.credentialId(2), chain.get(1).credentialId());
		assertEquals(ReporterRole.SECONDARY, chain.get(1).role());
		assertEquals(context.credentialId(3), chain.get(2).credentialId());
		assertEquals(ReporterRole.TERTIARY, chain.get(2).role());
	}

	private static final class TestContext {
		private final RelayStore store;
		private final ProjectRecord project;
		private final ReporterCoordinationService coordinationService;
		private final EnrollmentService enrollmentService;
		private final List<String> tokens;

		private TestContext(Path tempDir, int reporterCount) {
			this.store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
			store.initialize();
			this.project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);
			this.enrollmentService = new EnrollmentService(store, (projectId, force) -> {
			});
			this.coordinationService = new ReporterCoordinationService(store, (projectId, force) -> {
			}, LoggerFactory.getLogger("test"));
			java.util.ArrayList<String> enrolledTokens = new java.util.ArrayList<>();
			for (int index = 0; index < reporterCount; index++) {
				enrolledTokens.add(enroll("user-" + index, "Tester" + index, 1_000L + index));
			}
			this.tokens = List.copyOf(enrolledTokens);
		}

		private void makeEligible(int index, long nowMillis) {
			status(index, nowMillis);
			proof(index, nowMillis + 50L, "PROFESSION_XP|WC" + index + "|tester-" + index);
		}

		private ReporterCoordinationService.ActionResult status(int index, long nowMillis) {
			return coordinationService.handleStatus(tokens.get(index), new ReporterStatusRequest("1.0.0", project.projectId(), nowMillis), nowMillis);
		}

		private ReporterCoordinationService.ActionResult proof(int index, long nowMillis, String notificationKey) {
			return coordinationService.handleBombBellProof(tokens.get(index), new BombBellProofRequest("1.0.0", project.projectId(), nowMillis, notificationKey), nowMillis);
		}

		private ReporterCoordinationService.SubmitIntentActionResult intent(int index, long nowMillis, String snapshotHash) {
			return coordinationService.handleSubmitIntent(tokens.get(index), new ReporterSubmitIntentRequest("1.0.0", project.projectId(), nowMillis, snapshotHash), nowMillis);
		}

		private long credentialId(int index) {
			return store.authenticateDevice(project.projectId(), com.bombbellannouncer.relay.util.CryptoUtil.sha256Hex(tokens.get(index)), 100_000L).credentialId();
		}

		private String enroll(String discordUserId, String username, long nowMillis) {
			EnrollmentBundle bundle = enrollmentService.issueEnrollmentBundle("https://relay.example", project, discordUserId, username, nowMillis);
			EnrollmentRedeemResponse response = enrollmentService.redeemEnrollment(
				new EnrollmentRedeemRequest(project.projectId(), bundle.oneTimeCode(), "1.0.0"),
				nowMillis + 100L
			);
			return response.contributorToken();
		}
	}
}
