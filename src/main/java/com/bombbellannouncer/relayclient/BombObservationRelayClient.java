package com.bombbellannouncer.relayclient;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.protocol.BombBellProofRequest;
import com.bombbellannouncer.protocol.BombSnapshotItem;
import com.bombbellannouncer.protocol.BombSnapshotRequest;
import com.bombbellannouncer.protocol.BombSource;
import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.protocol.ReporterStatusRequest;
import com.bombbellannouncer.protocol.ReporterStatusResponse;
import com.bombbellannouncer.protocol.ReporterSubmitIntentRequest;
import com.bombbellannouncer.protocol.ReporterSubmitIntentResponse;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;

public final class BombObservationRelayClient implements BombObservationPublisher {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private static final String STATUS_PATH = "/api/v1/reporter/status";
	private static final String PROOF_PATH = "/api/v1/reporter/proof";
	private static final String INTENT_PATH = "/api/v1/reporter/intent";
	private static final String SNAPSHOT_PATH = "/api/v1/snapshots";

	private final BombbellAnnouncerConfig config;
	private final Logger logger;
	private final String clientVersion;
	private boolean authSuspended;
	private long suspendedConfigRevision = -1L;

	public BombObservationRelayClient(BombbellAnnouncerConfig config, Logger logger, String clientVersion) {
		this.config = config;
		this.logger = logger;
		this.clientVersion = clientVersion;
	}

	@Override
	public synchronized void requestPublish(Collection<BombInfo> activeBombs, boolean forceHeartbeat) {
		if (!canUseRelay()) {
			return;
		}

		long nowMillis = System.currentTimeMillis();
		ReporterRole reporterRole = config.reporterRole();
		if (forceHeartbeat) {
			ReporterStatusResponse statusResponse = sendReporterStatus(nowMillis);
			if (statusResponse != null && statusResponse.role() != null) {
				reporterRole = statusResponse.role();
			}
		}

		if (forceHeartbeat && !reporterRole.assignedToSubmitChain()) {
			return;
		}

		ReporterSubmitIntentResponse intentResponse = sendSubmitIntent(activeBombs, nowMillis);
		if (intentResponse == null || !intentResponse.canSubmit()) {
			return;
		}

		sendSnapshot(activeBombs, intentResponse.submitWindowSequence(), nowMillis);
	}

	@Override
	public synchronized void reportBombBell(String notificationKey) {
		if (!canUseRelay() || notificationKey == null || notificationKey.isBlank()) {
			return;
		}

		BombBellProofRequest payload = new BombBellProofRequest(
			clientVersion,
			config.projectId(),
			System.currentTimeMillis(),
			notificationKey.trim()
		);

		HttpRequest request = jsonRequest(PROOF_PATH, GSON.toJson(payload));
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			ReporterStatusResponse statusResponse = parseReporterStatusResponse(response.body());
			applyReporterState(statusResponse);
			handleAuthorizationFailure(response.statusCode(), response.body());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				logger.warn("Relay bomb-bell proof returned {}: {}", response.statusCode(), response.body());
			}
		} catch (Exception exception) {
			logger.warn("Failed to submit bomb-bell proof to relay", exception);
		}
	}

	@Override
	public synchronized void onClientReconnect() {
		authSuspended = false;
		suspendedConfigRevision = -1L;
	}

	private boolean canUseRelay() {
		if (!config.enabled() || !config.hasRelaySession()) {
			return false;
		}

		if (!authSuspended) {
			return true;
		}

		if (config.revision() == suspendedConfigRevision) {
			return false;
		}

		authSuspended = false;
		suspendedConfigRevision = -1L;
		return true;
	}

	private ReporterStatusResponse sendReporterStatus(long nowMillis) {
		ReporterStatusRequest payload = new ReporterStatusRequest(
			clientVersion,
			config.projectId(),
			nowMillis
		);

		HttpRequest request = jsonRequest(STATUS_PATH, GSON.toJson(payload));
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			ReporterStatusResponse statusResponse = parseReporterStatusResponse(response.body());
			applyReporterState(statusResponse);
			handleAuthorizationFailure(response.statusCode(), response.body());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				logger.warn("Relay reporter status returned {}: {}", response.statusCode(), response.body());
			}

			return statusResponse;
		} catch (Exception exception) {
			logger.warn("Failed to refresh relay reporter status", exception);
			return null;
		}
	}

	private ReporterSubmitIntentResponse sendSubmitIntent(Collection<BombInfo> activeBombs, long nowMillis) {
		ReporterSubmitIntentRequest payload = new ReporterSubmitIntentRequest(
			clientVersion,
			config.projectId(),
			nowMillis,
			buildSnapshotHash(activeBombs, nowMillis)
		);

		HttpRequest request = jsonRequest(INTENT_PATH, GSON.toJson(payload));
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			ReporterSubmitIntentResponse intentResponse = parseSubmitIntentResponse(response.body());
			applyReporterState(intentResponse);
			handleAuthorizationFailure(response.statusCode(), response.body());

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				logger.warn("Relay submit intent returned {}: {}", response.statusCode(), response.body());
			}

			return intentResponse;
		} catch (Exception exception) {
			logger.warn("Failed to submit snapshot intent to relay", exception);
			return null;
		}
	}

	private void sendSnapshot(Collection<BombInfo> activeBombs, long submitWindowSequence, long nowMillis) {
		List<BombSnapshotItem> bombs = activeBombs.stream()
			.filter(bombInfo -> bombInfo.isActive(nowMillis))
			.map(BombObservationRelayClient::toSnapshotItem)
			.sorted(Comparator
				.comparing(BombSnapshotItem::bombType)
				.thenComparing(BombSnapshotItem::server)
				.thenComparing(BombSnapshotItem::user)
				.thenComparingLong(BombSnapshotItem::expiresAtMillis))
			.toList();

		BombSnapshotRequest payload = new BombSnapshotRequest(
			clientVersion,
			config.projectId(),
			nowMillis,
			submitWindowSequence,
			bombs
		);

		HttpRequest request = jsonRequest(SNAPSHOT_PATH, GSON.toJson(payload));
		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			ReporterStatusResponse statusResponse = parseReporterStatusResponse(response.body());
			applyReporterState(statusResponse);

			if (response.statusCode() == 401 || response.statusCode() == 403) {
				handleAuthorizationFailure(response.statusCode(), response.body());
				return;
			}

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				if (response.statusCode() != 409) {
					logger.warn("Relay snapshot publish returned {}: {}", response.statusCode(), response.body());
				}
			}
		} catch (Exception exception) {
			logger.warn("Failed to publish bomb snapshot to relay", exception);
		}
	}

	private HttpRequest jsonRequest(String path, String body) {
		return HttpRequest.newBuilder(URI.create(config.relayBaseUrl() + path))
			.header("Authorization", "Bearer " + config.contributorToken())
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
	}

	private void handleAuthorizationFailure(int statusCode, String body) {
		if (statusCode != 401 && statusCode != 403) {
			return;
		}

		authSuspended = true;
		suspendedConfigRevision = config.revision();
		logger.warn("Relay publishing suspended after authorization failure: {}", body);
	}

	private void applyReporterState(ReporterStatusResponse response) {
		if (response == null || response.role() == null) {
			return;
		}

		applyReporterRole(response.role());
	}

	private void applyReporterState(ReporterSubmitIntentResponse response) {
		if (response == null || response.role() == null) {
			return;
		}

		applyReporterRole(response.role());
	}

	private void applyReporterRole(ReporterRole reporterRole) {
		long previousRevision = config.revision();
		config.setReporterRole(reporterRole);
		if (config.revision() != previousRevision) {
			config.save();
		}
	}

	private static ReporterStatusResponse parseReporterStatusResponse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}

		try {
			ReporterStatusResponse response = GSON.fromJson(body, ReporterStatusResponse.class);
			if (response == null || response.role() == null) {
				return null;
			}
			return response;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static ReporterSubmitIntentResponse parseSubmitIntentResponse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}

		try {
			ReporterSubmitIntentResponse response = GSON.fromJson(body, ReporterSubmitIntentResponse.class);
			if (response == null || response.role() == null) {
				return null;
			}
			return response;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String buildSnapshotHash(Collection<BombInfo> activeBombs, long nowMillis) {
		String payload = activeBombs.stream()
			.filter(bombInfo -> bombInfo.isActive(nowMillis))
			.sorted(Comparator
				.comparing((BombInfo bombInfo) -> bombInfo.bombType().name())
				.thenComparing(BombInfo::server)
				.thenComparing(BombInfo::user)
				.thenComparingLong(BombInfo::expiresAtMillis))
			.map(bombInfo -> bombInfo.bombType().name()
				+ "|" + bombInfo.server()
				+ "|" + bombInfo.user()
				+ "|" + bombInfo.startTimeMillis()
				+ "|" + bombInfo.expiresAtMillis()
				+ "|" + bombInfo.source().name())
			.reduce((left, right) -> left + "\n" + right)
			.orElse("empty");

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256 support", exception);
		}
	}

	private static BombSnapshotItem toSnapshotItem(BombInfo bombInfo) {
		return new BombSnapshotItem(
			BombType.valueOf(bombInfo.bombType().name()),
			bombInfo.server(),
			bombInfo.user(),
			bombInfo.startTimeMillis(),
			bombInfo.expiresAtMillis(),
			BombSource.valueOf(bombInfo.source().name())
		);
	}
}
