package com.bombbellannouncer.relay;

import com.bombbellannouncer.relay.config.RelayConfig;
import com.bombbellannouncer.relay.discord.DiscordApiClient;
import com.bombbellannouncer.relay.discord.DiscordDashboardRenderer;
import com.bombbellannouncer.relay.http.RelayHttpServer;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.service.DashboardSyncService;
import com.bombbellannouncer.relay.service.DiscordCommandService;
import com.bombbellannouncer.relay.service.EnrollmentService;
import com.bombbellannouncer.relay.service.ReporterCoordinationService;
import com.bombbellannouncer.relay.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BombbellRelayApplication {
	private static final Logger LOGGER = LoggerFactory.getLogger(BombbellRelayApplication.class);

	private BombbellRelayApplication() {
	}

	public static void main(String[] args) {
		RelayConfig config = RelayConfig.load();
		LOGGER.info("Loaded relay config from {}", config.configSource());
		LOGGER.info("Relay base URL is {}", config.baseUrl());
		LOGGER.info(
			"Discord application id={} publicKeyPrefix={}",
			config.discordApplicationId(),
			config.discordPublicKey().substring(0, Math.min(8, config.discordPublicKey().length()))
		);
		RelayStore store = new RelayStore(config.jdbcUrl());
		store.initialize();

		DiscordApiClient discordApiClient = new DiscordApiClient(LOGGER, config.discordBotToken(), config.discordApplicationId());
		DiscordDashboardRenderer renderer = new DiscordDashboardRenderer();
		DashboardSyncService dashboardSyncService = new DashboardSyncService(store, discordApiClient, renderer, LOGGER);
		EnrollmentService enrollmentService = new EnrollmentService(store, dashboardSyncService);
		ReporterCoordinationService reporterCoordinationService = new ReporterCoordinationService(store, dashboardSyncService, LOGGER);
		SnapshotService snapshotService = new SnapshotService(store, enrollmentService, dashboardSyncService, reporterCoordinationService);
		DiscordCommandService commandService = new DiscordCommandService(store, config, enrollmentService, dashboardSyncService);

		dashboardSyncService.start();
		reporterCoordinationService.start();
		registerCommands(discordApiClient);

		RelayHttpServer httpServer = new RelayHttpServer(
			config,
			commandService,
			enrollmentService,
			reporterCoordinationService,
			snapshotService,
			LOGGER
		);
		httpServer.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			httpServer.close();
			reporterCoordinationService.close();
			dashboardSyncService.close();
		}));
	}

	private static void registerCommands(DiscordApiClient discordApiClient) {
		DiscordApiClient.DiscordApiResponse response = discordApiClient.registerGlobalCommands();
		if (!response.isSuccess()) {
			LOGGER.warn("Failed to register Discord slash commands: {} {}", response.statusCode(), response.body());
			return;
		}

		LOGGER.info("Registered Discord slash commands.");
	}
}
