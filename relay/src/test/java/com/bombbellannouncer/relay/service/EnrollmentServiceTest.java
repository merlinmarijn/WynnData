package com.bombbellannouncer.relay.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EnrollmentServiceTest {
	@Test
	void enrollmentCodesCanOnlyBeRedeemedOnce(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();

		long nowMillis = 1_000L;
		ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", nowMillis);
		EnrollmentService service = new EnrollmentService(store, (projectId, force) -> {
		});
		EnrollmentBundle bundle = service.issueEnrollmentBundle("https://relay.example", project, "user-1", "Tester", nowMillis);

		EnrollmentRedeemResponse firstRedeem = service.redeemEnrollment(
			new EnrollmentRedeemRequest(bundle.projectId(), bundle.oneTimeCode(), "1.0.0"),
			nowMillis + 100L
		);
		EnrollmentRedeemResponse secondRedeem = service.redeemEnrollment(
			new EnrollmentRedeemRequest(bundle.projectId(), bundle.oneTimeCode(), "1.0.0"),
			nowMillis + 200L
		);

		assertTrue(firstRedeem.success());
		assertFalse(secondRedeem.success());
	}
}
