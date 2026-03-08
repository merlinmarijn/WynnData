package com.bombbellannouncer.relay.http;

import com.bombbellannouncer.protocol.BombBellProofRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemRequest;
import com.bombbellannouncer.protocol.EnrollmentRedeemResponse;
import com.bombbellannouncer.protocol.BombSnapshotRequest;
import com.bombbellannouncer.protocol.ReporterStatusRequest;
import com.bombbellannouncer.protocol.ReporterSubmitIntentRequest;
import com.bombbellannouncer.relay.config.RelayConfig;
import com.bombbellannouncer.relay.service.DiscordCommandService;
import com.bombbellannouncer.relay.service.EnrollmentService;
import com.bombbellannouncer.relay.service.ReporterCoordinationService;
import com.bombbellannouncer.relay.service.SnapshotService;
import com.bombbellannouncer.relay.util.CryptoUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

public final class RelayHttpServer implements AutoCloseable {
	private static final Gson GSON = new Gson();
	private static final Path INTERACTION_DEBUG_LOG = Path.of("relay", "interaction-debug.log");

	private final RelayConfig config;
	private final DiscordCommandService commandService;
	private final EnrollmentService enrollmentService;
	private final ReporterCoordinationService reporterCoordinationService;
	private final SnapshotService snapshotService;
	private final Logger logger;
	private final HttpServer server;

	public RelayHttpServer(
		RelayConfig config,
		DiscordCommandService commandService,
		EnrollmentService enrollmentService,
		ReporterCoordinationService reporterCoordinationService,
		SnapshotService snapshotService,
		Logger logger
	) {
		this.config = config;
		this.commandService = commandService;
		this.enrollmentService = enrollmentService;
		this.reporterCoordinationService = reporterCoordinationService;
		this.snapshotService = snapshotService;
		this.logger = logger;

		try {
			this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to create relay HTTP server", exception);
		}
	}

	public void start() {
		server.createContext("/health", this::handleHealth);
		server.createContext("/discord/interactions", this::handleDiscordInteraction);
		server.createContext("/api/v1/enrollment/redeem", this::handleEnrollmentRedeem);
		server.createContext("/api/v1/reporter/status", this::handleReporterStatus);
		server.createContext("/api/v1/reporter/proof", this::handleBombBellProof);
		server.createContext("/api/v1/reporter/intent", this::handleReporterSubmitIntent);
		server.createContext("/api/v1/snapshots", this::handleSnapshot);
		server.createContext("/api/v1/session/disconnect", this::handleDisconnect);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		logger.info("Relay HTTP server listening on {}", config.baseUrl());
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		writeJson(exchange, 200, GSON.toJson(root));
	}

	private void handleDiscordInteraction(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String signature = exchange.getRequestHeaders().getFirst("X-Signature-Ed25519");
		String timestamp = exchange.getRequestHeaders().getFirst("X-Signature-Timestamp");
		byte[] body = exchange.getRequestBody().readAllBytes();
		logger.info(
			"Received Discord interaction. timestampPresent={} signaturePresent={} bodyLength={}",
			timestamp != null,
			signature != null,
			body.length
		);
		appendInteractionDebug(
			"received remote=" + exchange.getRemoteAddress()
				+ " timestampPresent=" + (timestamp != null)
				+ " signaturePresent=" + (signature != null)
				+ " signaturePrefix=" + abbreviate(signature)
				+ " bodyLength=" + body.length
		);

		if (signature == null || timestamp == null || !CryptoUtil.verifyDiscordSignature(config.discordPublicKey(), timestamp, body, signature)) {
			logger.warn(
				"Rejected Discord interaction due to invalid signature. publicKeyPrefix={} timestampPresent={} signaturePresent={} bodyLength={}",
				config.discordPublicKey().substring(0, Math.min(8, config.discordPublicKey().length())),
				timestamp != null,
				signature != null,
				body.length
			);
			appendInteractionDebug(
				"invalid-signature publicKeyPrefix=" + abbreviate(config.discordPublicKey())
					+ " timestampPresent=" + (timestamp != null)
					+ " signaturePresent=" + (signature != null)
					+ " bodyLength=" + body.length
			);
			writeJson(exchange, 401, jsonMessage("Invalid Discord signature."));
			return;
		}

		try {
			JsonObject interaction = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
			appendInteractionDebug(
				"accepted interactionType=" + (interaction.has("type") ? interaction.get("type").getAsInt() : -1)
					+ " interactionId=" + (interaction.has("id") ? interaction.get("id").getAsString() : "")
					+ " guildId=" + (interaction.has("guild_id") ? interaction.get("guild_id").getAsString() : "")
			);
			logger.info(
				"Accepted Discord interaction. type={} id={} guildId={}",
				interaction.has("type") ? interaction.get("type").getAsInt() : -1,
				interaction.has("id") ? interaction.get("id").getAsString() : "",
				interaction.has("guild_id") ? interaction.get("guild_id").getAsString() : ""
			);
			writeJson(exchange, 200, GSON.toJson(commandService.handleInteraction(interaction)));
		} catch (Exception exception) {
			appendInteractionDebug("handler-exception " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
			logger.warn("Failed to handle Discord interaction after signature verification", exception);
			writeJson(exchange, 500, jsonMessage("Failed to process Discord interaction."));
		}
	}

	private void handleEnrollmentRedeem(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			EnrollmentRedeemRequest request = GSON.fromJson(body, EnrollmentRedeemRequest.class);
			EnrollmentRedeemResponse response = enrollmentService.redeemEnrollment(request, System.currentTimeMillis());
			writeJson(exchange, response.success() ? 200 : 400, GSON.toJson(response));
		} catch (Exception exception) {
			logger.warn("Failed to handle enrollment redeem", exception);
			writeJson(exchange, 400, jsonMessage("Invalid enrollment request."));
		}
	}

	private void handleSnapshot(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String bearerToken = bearerToken(exchange);
		if (bearerToken.isBlank()) {
			writeJson(exchange, 401, jsonMessage("Missing bearer token."));
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			BombSnapshotRequest request = GSON.fromJson(body, BombSnapshotRequest.class);
			SnapshotService.ActionResult result = snapshotService.ingestSnapshot(bearerToken, request, System.currentTimeMillis());
			writeJson(exchange, result.statusCode(), GSON.toJson(result.response()));
		} catch (Exception exception) {
			logger.warn("Failed to handle snapshot request", exception);
			writeJson(exchange, 400, jsonMessage("Invalid snapshot request."));
		}
	}

	private void handleReporterStatus(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String bearerToken = bearerToken(exchange);
		if (bearerToken.isBlank()) {
			writeJson(exchange, 401, jsonMessage("Missing bearer token."));
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			ReporterStatusRequest request = GSON.fromJson(body, ReporterStatusRequest.class);
			ReporterCoordinationService.ActionResult result = reporterCoordinationService.handleStatus(bearerToken, request, System.currentTimeMillis());
			writeJson(exchange, result.statusCode(), GSON.toJson(result.response()));
		} catch (Exception exception) {
			logger.warn("Failed to handle reporter status request", exception);
			writeJson(exchange, 400, jsonMessage("Invalid reporter status request."));
		}
	}

	private void handleBombBellProof(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String bearerToken = bearerToken(exchange);
		if (bearerToken.isBlank()) {
			writeJson(exchange, 401, jsonMessage("Missing bearer token."));
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			BombBellProofRequest request = GSON.fromJson(body, BombBellProofRequest.class);
			ReporterCoordinationService.ActionResult result = reporterCoordinationService.handleBombBellProof(bearerToken, request, System.currentTimeMillis());
			writeJson(exchange, result.statusCode(), GSON.toJson(result.response()));
		} catch (Exception exception) {
			logger.warn("Failed to handle bomb-bell proof request", exception);
			writeJson(exchange, 400, jsonMessage("Invalid bomb-bell proof request."));
		}
	}

	private void handleReporterSubmitIntent(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String bearerToken = bearerToken(exchange);
		if (bearerToken.isBlank()) {
			writeJson(exchange, 401, jsonMessage("Missing bearer token."));
			return;
		}

		try {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			ReporterSubmitIntentRequest request = GSON.fromJson(body, ReporterSubmitIntentRequest.class);
			ReporterCoordinationService.SubmitIntentActionResult result = reporterCoordinationService.handleSubmitIntent(bearerToken, request, System.currentTimeMillis());
			writeJson(exchange, result.statusCode(), GSON.toJson(result.response()));
		} catch (Exception exception) {
			logger.warn("Failed to handle reporter submit intent request", exception);
			writeJson(exchange, 400, jsonMessage("Invalid reporter submit intent request."));
		}
	}

	private void handleDisconnect(HttpExchange exchange) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, jsonMessage("Method not allowed."));
			return;
		}

		String bearerToken = bearerToken(exchange);
		if (bearerToken.isBlank()) {
			writeJson(exchange, 401, jsonMessage("Missing bearer token."));
			return;
		}

		try {
			JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			String projectId = request.has("projectId") ? request.get("projectId").getAsString() : "";
			EnrollmentService.ActionResult result = enrollmentService.disconnectDevice(projectId, bearerToken, System.currentTimeMillis());
			writeJson(exchange, result.success() ? 200 : 401, jsonMessage(result.message()));
		} catch (Exception exception) {
			logger.warn("Failed to handle disconnect request", exception);
			writeJson(exchange, 400, jsonMessage("Invalid disconnect request."));
		}
	}

	private static String bearerToken(HttpExchange exchange) {
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			return "";
		}
		return authorization.substring("Bearer ".length()).trim();
	}

	private static String jsonMessage(String message) {
		JsonObject root = new JsonObject();
		root.addProperty("message", message);
		return GSON.toJson(root);
	}

	private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static void appendInteractionDebug(String line) {
		try {
			Files.createDirectories(INTERACTION_DEBUG_LOG.getParent());
			Files.writeString(
				INTERACTION_DEBUG_LOG,
				Instant.now() + " " + line + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException ignored) {
		}
	}

	private static String abbreviate(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.substring(0, Math.min(12, value.length()));
	}
}
