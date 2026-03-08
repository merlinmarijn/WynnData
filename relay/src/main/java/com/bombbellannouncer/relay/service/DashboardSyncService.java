package com.bombbellannouncer.relay.service;

import com.bombbellannouncer.relay.discord.DiscordApiClient;
import com.bombbellannouncer.relay.discord.DiscordApiClient.DiscordApiResponse;
import com.bombbellannouncer.relay.discord.DiscordDashboardRenderer;
import com.bombbellannouncer.relay.discord.DiscordDashboardRenderer.RenderedDashboardMessage;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardMessageRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterChainSlotRecord;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class DashboardSyncService implements AutoCloseable, DashboardSyncRequester {
	private static final long DASHBOARD_LAYOUT_VERSION = 2L;

	private final RelayStore store;
	private final DiscordApiClient discordApiClient;
	private final DiscordDashboardRenderer renderer;
	private final Logger logger;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "bombbell-relay-dashboard");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, Boolean> pendingProjectSyncs = new ConcurrentHashMap<>();

	public DashboardSyncService(RelayStore store, DiscordApiClient discordApiClient, DiscordDashboardRenderer renderer, Logger logger) {
		this.store = store;
		this.discordApiClient = discordApiClient;
		this.renderer = renderer;
		this.logger = logger;
	}

	public void start() {
		executor.scheduleWithFixedDelay(this::runHousekeeping, 10L, 10L, TimeUnit.SECONDS);
		executor.scheduleWithFixedDelay(this::drainPendingSyncs, 1L, 1L, TimeUnit.SECONDS);
	}

	@Override
	public void requestSync(String projectId, boolean force) {
		pendingProjectSyncs.merge(projectId, force, (left, right) -> left || right);
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	private void runHousekeeping() {
		long nowMillis = System.currentTimeMillis();
		store.deleteExpiredBombs(nowMillis);
		for (ProjectRecord project : store.findAllProjects()) {
			requestSync(project.projectId(), false);
		}
	}

	private void drainPendingSyncs() {
		Map<String, Boolean> snapshot = new ConcurrentHashMap<>();
		for (String projectId : pendingProjectSyncs.keySet()) {
			Boolean force = pendingProjectSyncs.remove(projectId);
			if (force != null) {
				snapshot.put(projectId, force);
			}
		}

		for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
			syncProject(entry.getKey(), entry.getValue());
		}
	}

	private void syncProject(String projectId, boolean force) {
		try {
			long nowMillis = System.currentTimeMillis();
			store.deleteExpiredBombs(nowMillis);
			ProjectRecord project = store.findProjectById(projectId);
			if (project == null) {
				return;
			}

			Map<String, DashboardMessageRecord> messageRecords = store.findDashboardMessages(projectId);
			java.util.List<RenderedDashboardMessage> renderedMessages = renderer.render(
				store.findActiveBombs(projectId, nowMillis),
				store.findConnectedReporterDevices(projectId, nowMillis - ReporterCoordinationService.STATUS_STALE_MILLIS),
				store.findReporterChain(projectId),
				store.findDashboardBombTypeSettings(projectId),
				store.findDashboardCombos(projectId)
			);
			if (store.findProjectDashboardLayoutVersion(projectId) != DASHBOARD_LAYOUT_VERSION || requiresFullRebuild(messageRecords, renderedMessages)) {
				rebuildDashboard(project, messageRecords, renderedMessages, nowMillis);
				return;
			}

			for (RenderedDashboardMessage renderedMessage : renderedMessages) {
				DashboardMessageRecord messageRecord = messageRecords.get(renderedMessage.slotId());
				boolean missingMessage = messageRecord == null || messageRecord.messageId().isBlank();
				boolean payloadChanged = missingMessage || !Objects.equals(messageRecord.payloadHash(), renderedMessage.hash());

				if (!missingMessage && !force && !payloadChanged) {
					continue;
				}

				DiscordApiResponse response = missingMessage
					? sendWithRetry(() -> discordApiClient.createMessage(project.channelId(), renderedMessage.payloadJson()))
					: sendWithRetry(() -> discordApiClient.editMessage(project.channelId(), messageRecord.messageId(), renderedMessage.payloadJson()));

				if (response.isUnauthorized()) {
					logger.warn("Discord dashboard sync unauthorized for project {}: {}", projectId, response.body());
					return;
				}

				if (!missingMessage && response.isNotFound()) {
					response = sendWithRetry(() -> discordApiClient.createMessage(project.channelId(), renderedMessage.payloadJson()));
				}

				if (response.isSuccess() && response.messageId() != null && !response.messageId().isBlank()) {
					store.upsertDashboardMessage(projectId, renderedMessage.slotId(), response.messageId(), renderedMessage.hash(), nowMillis);
					continue;
				}

				logger.warn("Discord dashboard sync failed for project {} slot {}: {} {}", projectId, renderedMessage.slotId(), response.statusCode(), response.body());
			}
		} catch (Exception exception) {
			logger.warn("Failed to sync dashboard project {}", projectId, exception);
		}
	}

	private boolean requiresFullRebuild(Map<String, DashboardMessageRecord> messageRecords, java.util.List<RenderedDashboardMessage> renderedMessages) {
		if (messageRecords.isEmpty()) {
			return false;
		}
		if (messageRecords.size() != renderedMessages.size()) {
			return true;
		}
		for (RenderedDashboardMessage renderedMessage : renderedMessages) {
			if (!messageRecords.containsKey(renderedMessage.slotId())) {
				return true;
			}
		}
		return false;
	}

	private void rebuildDashboard(
		ProjectRecord project,
		Map<String, DashboardMessageRecord> existingMessages,
		java.util.List<RenderedDashboardMessage> renderedMessages,
		long nowMillis
	) {
		for (DashboardMessageRecord messageRecord : existingMessages.values()) {
			if (messageRecord.messageId() == null || messageRecord.messageId().isBlank()) {
				continue;
			}

			DiscordApiResponse deleteResponse = sendWithRetry(() -> discordApiClient.deleteMessage(project.channelId(), messageRecord.messageId()));
			if (deleteResponse.isUnauthorized()) {
				logger.warn("Discord dashboard rebuild unauthorized for project {}: {}", project.projectId(), deleteResponse.body());
				return;
			}
		}

		store.clearDashboardMessages(project.projectId());
		store.setProjectDashboardLayoutVersion(project.projectId(), DASHBOARD_LAYOUT_VERSION);

		for (RenderedDashboardMessage renderedMessage : renderedMessages) {
			DiscordApiResponse response = sendWithRetry(() -> discordApiClient.createMessage(project.channelId(), renderedMessage.payloadJson()));
			if (response.isUnauthorized()) {
				logger.warn("Discord dashboard rebuild unauthorized for project {}: {}", project.projectId(), response.body());
				return;
			}
			if (!response.isSuccess() || response.messageId() == null || response.messageId().isBlank()) {
				logger.warn("Discord dashboard rebuild failed for project {} slot {}: {} {}", project.projectId(), renderedMessage.slotId(), response.statusCode(), response.body());
				return;
			}

			store.upsertDashboardMessage(project.projectId(), renderedMessage.slotId(), response.messageId(), renderedMessage.hash(), nowMillis);
		}
	}

	private DiscordApiResponse sendWithRetry(DiscordRequest request) {
		for (int attempt = 0; attempt < 5; attempt++) {
			DiscordApiResponse response = request.execute();
			if (!response.isRateLimited()) {
				return response;
			}

			long retryAfterMillis = response.retryAfterMillis() > 0L ? response.retryAfterMillis() : 1_000L;
			try {
				Thread.sleep(retryAfterMillis);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return new DiscordApiResponse(0, exception.getMessage(), "", 0L);
			}
		}

		return new DiscordApiResponse(429, "Retry limit exceeded", "", 0L);
	}

	@FunctionalInterface
	private interface DiscordRequest {
		DiscordApiResponse execute();
	}
}
