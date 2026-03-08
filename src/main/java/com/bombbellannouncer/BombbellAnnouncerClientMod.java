package com.bombbellannouncer;

import com.bombbellannouncer.command.WynnDataClientCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.relayclient.BombObservationPublisher;
import com.bombbellannouncer.relayclient.BombObservationRelayClient;
import com.bombbellannouncer.relayclient.DebouncedBombObservationPublisher;
import com.bombbellannouncer.relayclient.RelayEnrollmentService;
import com.bombbellannouncer.tracker.BombTrackerController;

public final class BombbellAnnouncerClientMod implements ClientModInitializer {
	private static BombbellAnnouncerConfig config;
	private static BombObservationPublisher observationPublisher;
	private static RelayEnrollmentService enrollmentService;
	private static BombTrackerController tracker;

	@Override
	public void onInitializeClient() {
		BombbellAnnouncerConfig loadedConfig = getConfig();
		tracker = new BombTrackerController(loadedConfig, getObservationPublisher());
		tracker.register();
		WynnDataClientCommands.register(() -> tracker, loadedConfig);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			getObservationPublisher().onClientReconnect();
			client.execute(() -> {
				if (client.player != null) {
					client.player.sendMessage(Text.literal("[WynnData] Mod loaded successfully."), false);
				}
				if (tracker != null) {
					getObservationPublisher().requestPublish(tracker.getActiveBombsSnapshot(System.currentTimeMillis()), true);
				}
			});
		});
	}

	public static BombbellAnnouncerConfig getConfig() {
		if (config == null) {
			config = BombbellAnnouncerConfig.load(
				FabricLoader.getInstance().getConfigDir(),
				BombbellAnnouncerMod.LOGGER
			);
		}

		return config;
	}

	public static BombObservationPublisher getObservationPublisher() {
		if (observationPublisher == null) {
			observationPublisher = new DebouncedBombObservationPublisher(
				new BombObservationRelayClient(getConfig(), BombbellAnnouncerMod.LOGGER, getModVersion()),
				1_000L
			);
		}

		return observationPublisher;
	}

	public static RelayEnrollmentService getEnrollmentService() {
		if (enrollmentService == null) {
			enrollmentService = new RelayEnrollmentService(getConfig(), BombbellAnnouncerMod.LOGGER, getModVersion());
		}

		return enrollmentService;
	}

	public static RelayEnrollmentService.ActionResult redeemSetupBundle(String rawBundle) {
		RelayEnrollmentService.ActionResult result = getEnrollmentService().redeemSetupBundle(rawBundle);
		if (result.success() && tracker != null) {
			getObservationPublisher().requestPublish(tracker.getActiveBombsSnapshot(System.currentTimeMillis()), true);
		}
		return result;
	}

	public static RelayEnrollmentService.ActionResult disconnectRelaySession() {
		return getEnrollmentService().disconnectCurrentSession();
	}

	private static String getModVersion() {
		return FabricLoader.getInstance()
			.getModContainer(BombbellAnnouncerMod.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}
}
