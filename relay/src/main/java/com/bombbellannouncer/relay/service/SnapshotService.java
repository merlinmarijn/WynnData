package com.bombbellannouncer.relay.service;

import com.bombbellannouncer.protocol.BombSnapshotItem;
import com.bombbellannouncer.protocol.BombSnapshotRequest;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.protocol.ReporterStatusResponse;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.AuthenticatedDevice;

public final class SnapshotService {
	private final RelayStore store;
	private final EnrollmentService enrollmentService;
	private final DashboardSyncRequester syncRequester;
	private final ReporterCoordinationService reporterCoordinationService;

	public SnapshotService(
		RelayStore store,
		EnrollmentService enrollmentService,
		DashboardSyncRequester syncRequester,
		ReporterCoordinationService reporterCoordinationService
	) {
		this.store = store;
		this.enrollmentService = enrollmentService;
		this.syncRequester = syncRequester;
		this.reporterCoordinationService = reporterCoordinationService;
	}

	public ActionResult ingestSnapshot(String rawToken, BombSnapshotRequest request, long nowMillis) {
		if (request == null || request.projectId() == null || request.projectId().isBlank()) {
			return new ActionResult(400, ReporterStatusResponse.failure("Invalid snapshot request.", ReporterRole.INELIGIBLE));
		}

		AuthenticatedDevice device = enrollmentService.authenticate(request.projectId(), rawToken, nowMillis);
		if (device == null) {
			return new ActionResult(401, ReporterStatusResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE));
		}

		ReporterCoordinationService.SnapshotAuthorization authorization = reporterCoordinationService.authorizeSnapshot(device, request, nowMillis);
		if (!authorization.allowed()) {
			return new ActionResult(authorization.statusCode(), authorization.response());
		}

		if (request.bombs() != null) {
			for (BombSnapshotItem bomb : request.bombs()) {
				store.mergeBombObservation(device.projectId(), bomb, nowMillis);
			}
		}

		syncRequester.requestSync(device.projectId(), false);
		return new ActionResult(200, authorization.response());
	}

	public record ActionResult(int statusCode, ReporterStatusResponse response) {
	}
}
