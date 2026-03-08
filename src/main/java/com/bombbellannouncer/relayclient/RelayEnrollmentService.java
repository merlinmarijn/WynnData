package com.bombbellannouncer.relayclient;

import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;

public final class RelayEnrollmentService {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final BombbellAnnouncerConfig config;
	private final Logger logger;
	private final String clientVersion;

	public RelayEnrollmentService(BombbellAnnouncerConfig config, Logger logger, String clientVersion) {
		this.config = config;
		this.logger = logger;
		this.clientVersion = clientVersion;
	}

	public ActionResult redeemSetupBundle(String rawBundle) {
		EnrollmentBundle bundle;
		try {
			bundle = RelaySetupBundleCodec.decode(rawBundle, System.currentTimeMillis());
		} catch (IllegalArgumentException exception) {
			return new ActionResult(false, exception.getMessage());
		}

		EnrollmentRedeemRequest requestPayload = new EnrollmentRedeemRequest(
			bundle.projectId(),
			bundle.oneTimeCode(),
			clientVersion
		);

		HttpRequest request = HttpRequest.newBuilder(URI.create(bundle.relayBaseUrl() + "/api/v1/enrollment/redeem"))
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestPayload), StandardCharsets.UTF_8))
			.build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return new ActionResult(false, "Relay rejected enrollment: HTTP " + response.statusCode());
			}

			EnrollmentRedeemResponse redeemResponse = GSON.fromJson(response.body(), EnrollmentRedeemResponse.class);
			if (redeemResponse == null || !redeemResponse.success() || redeemResponse.contributorToken() == null || redeemResponse.contributorToken().isBlank()) {
				String message = redeemResponse == null ? "Enrollment failed." : redeemResponse.message();
				return new ActionResult(false, message == null || message.isBlank() ? "Enrollment failed." : message);
			}

			config.storeContributorSession(
				bundle.relayBaseUrl(),
				redeemResponse.projectId(),
				redeemResponse.contributorToken(),
				redeemResponse.linkedDiscordUser(),
				redeemResponse.dashboardName()
			);
			config.save();
			return new ActionResult(true, "Connected to " + config.dashboardName() + " as " + config.linkedDiscordUser() + ".");
		} catch (Exception exception) {
			logger.warn("Failed to redeem relay enrollment bundle", exception);
			return new ActionResult(false, "Failed to connect to relay.");
		}
	}

	public ActionResult disconnectCurrentSession() {
		if (!config.hasRelaySession()) {
			clearLocalSession();
			return new ActionResult(true, "Local relay session cleared.");
		}

		String relayBaseUrl = config.relayBaseUrl();
		String contributorToken = config.contributorToken();
		String projectId = config.projectId();

		HttpRequest request = HttpRequest.newBuilder(URI.create(relayBaseUrl + "/api/v1/session/disconnect"))
			.header("Authorization", "Bearer " + contributorToken)
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(buildDisconnectPayload(projectId), StandardCharsets.UTF_8))
			.build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			clearLocalSession();
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return new ActionResult(true, "Relay session disconnected.");
			}

			return new ActionResult(true, "Local session cleared, but relay returned HTTP " + response.statusCode() + ".");
		} catch (Exception exception) {
			logger.warn("Failed to disconnect relay session", exception);
			clearLocalSession();
			return new ActionResult(true, "Local session cleared, but relay revocation failed.");
		}
	}

	private static String buildDisconnectPayload(String projectId) {
		JsonObject root = new JsonObject();
		root.addProperty("projectId", projectId);
		return GSON.toJson(root);
	}

	private void clearLocalSession() {
		config.clearContributorSession();
		config.save();
	}

	public record ActionResult(boolean success, String message) {
	}
}
