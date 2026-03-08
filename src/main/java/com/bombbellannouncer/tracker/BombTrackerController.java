package com.bombbellannouncer.tracker;

import com.bombbellannouncer.BombbellAnnouncerMod;
import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombKey;
import com.bombbellannouncer.bomb.BombMessageParser;
import com.bombbellannouncer.bomb.BombSource;
import com.bombbellannouncer.bomb.BombType;
import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.relayclient.BombObservationPublisher;
import com.bombbellannouncer.subscription.SubscriptionEvaluator;
import com.bombbellannouncer.subscription.SubscriptionMatch;
import com.bombbellannouncer.subscription.SubscriptionNotificationState;
import com.bombbellannouncer.subscription.SubscriptionTarget;
import com.bombbellannouncer.mixin.client.BossBarHudAccessor;
import com.bombbellannouncer.mixin.client.ChatHudAccessor;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class BombTrackerController {
	private static final int WORLD_POLL_INTERVAL_TICKS = 10;
	private static final int CHAT_BACKFILL_INTERVAL_TICKS = 200;
	private static final int SNAPSHOT_HEARTBEAT_INTERVAL_TICKS = 1_200;
	private static final long PROCESSED_MESSAGE_TTL_MILLIS = 15_000L;

	private final BombbellAnnouncerConfig config;
	private final BombObservationPublisher observationPublisher;
	private final BombMessageParser parser = new BombMessageParser();
	private final RecentNotificationCache recentNotifications = new RecentNotificationCache(5_000L);
	private final RecentNotificationCache recentProcessedMessages = new RecentNotificationCache(PROCESSED_MESSAGE_TTL_MILLIS);
	private final ActiveBombContainer activeBombs = new ActiveBombContainer();
	private final Map<BombType, BombInfo> currentServerBombs = new EnumMap<>(BombType.class);
	private final SubscriptionEvaluator subscriptionEvaluator = new SubscriptionEvaluator();
	private final SubscriptionNotificationState subscriptionNotificationState = new SubscriptionNotificationState();

	private String currentWorldName = "";
	private int tickCounter;
	private int chatBackfillTickCounter;
	private int snapshotHeartbeatTickCounter;
	private int lastScannedChatCreationTick = Integer.MIN_VALUE;

	public BombTrackerController(BombbellAnnouncerConfig config, BombObservationPublisher observationPublisher) {
		this.config = config;
		this.observationPublisher = observationPublisher;
	}

	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleIncomingMessage(message));
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> handleIncomingMessage(message));
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> onWorldChange());

		if (!config.enabled()) {
			BombbellAnnouncerMod.LOGGER.info("Bomb bell tracker initialized with relay publishing disabled.");
		} else if (!config.hasRelaySession()) {
			BombbellAnnouncerMod.LOGGER.info("Bomb bell tracker initialized without a relay contributor session.");
		}
	}

	public Collection<BombInfo> getActiveBombsSnapshot(long nowMillis) {
		pruneExpiredBombs(nowMillis);
		return activeBombs.asCollection();
	}

	public void onSubscriptionAdded(SubscriptionTarget subscriptionTarget) {
		long nowMillis = System.currentTimeMillis();
		subscriptionNotificationState.clearTarget(subscriptionTarget);
		List<SubscriptionMatch> matches = subscriptionEvaluator.findMatchesForTarget(getActiveBombsSnapshot(nowMillis), subscriptionTarget, nowMillis);
		for (SubscriptionMatch match : subscriptionNotificationState.collectNewMatches(matches)) {
			sendSubscriptionNotification(match, nowMillis);
		}
	}

	public void onSubscriptionRemoved(SubscriptionTarget subscriptionTarget) {
		subscriptionNotificationState.clearTarget(subscriptionTarget);
	}

	public void onSubscriptionsCleared() {
		subscriptionNotificationState.clearAll();
	}

	private void handleIncomingMessage(Text message) {
		long nowMillis = System.currentTimeMillis();
		boolean changed = pruneExpiredBombs(nowMillis);
		recentNotifications.prune(nowMillis);
		recentProcessedMessages.prune(nowMillis);

		String plainText = parser.normalizePlainText(message.getString());
		if (plainText.isEmpty()) {
			return;
		}

		Optional<BombMessageParser.BombBellMatch> bombBellMatch = parser.parseBombBell(plainText);
		if (bombBellMatch.isPresent()) {
			if (!recentProcessedMessages.shouldSend("bell|" + plainText, nowMillis)) {
				return;
			}
			observationPublisher.reportBombBell(bombBellMatch.get().notificationKey());
			if (addBombFromChat(bombBellMatch.get(), nowMillis)) {
				scheduleSnapshotPublish(false, nowMillis);
			} else if (changed) {
				scheduleSnapshotPublish(false, nowMillis);
			}
			return;
		}

		Optional<BombType> localBombThrown = parser.parseLocalBombThrown(plainText);
		if (localBombThrown.isPresent()) {
			if (!recentProcessedMessages.shouldSend("local|" + plainText, nowMillis)) {
				return;
			}
			if (addLocalBomb(localBombThrown.get(), nowMillis)) {
				scheduleSnapshotPublish(false, nowMillis);
			} else if (changed) {
				scheduleSnapshotPublish(false, nowMillis);
			}
			return;
		}

		parser.parseBombExpiration(plainText).ifPresent(bombType -> {
			if (recentProcessedMessages.shouldSend("expired|" + plainText, nowMillis)) {
				if (expireCurrentWorldBomb(bombType) || changed) {
					scheduleSnapshotPublish(false, nowMillis);
				}
			}
		});

		if (changed) {
			scheduleSnapshotPublish(false, nowMillis);
		}
	}

	private boolean addBombFromChat(BombMessageParser.BombBellMatch match, long nowMillis) {
		if (!recentNotifications.shouldSend(match.notificationKey(), nowMillis)) {
			return false;
		}

		BombInfo bombInfo = new BombInfo(
			match.user(),
			match.bombType(),
			match.server(),
			nowMillis,
			match.bombType().activeMinutes(),
			BombSource.CHAT_BELL
		);

		return activeBombs.forceAdd(bombInfo);
	}

	private boolean addLocalBomb(BombType bombType, long nowMillis) {
		if (currentWorldName.isBlank()) {
			return false;
		}

		return activeBombs.forceAdd(new BombInfo(
			"",
			bombType,
			currentWorldName,
			nowMillis,
			bombType.activeMinutes(),
			BombSource.CHAT_BELL
		));
	}

	private boolean expireCurrentWorldBomb(BombType bombType) {
		BombInfo removed = currentServerBombs.remove(bombType);
		return removed != null && activeBombs.removeIfEquals(removed);
	}

	private void onEndClientTick(MinecraftClient client) {
		long nowMillis = System.currentTimeMillis();
		boolean changed = pruneExpiredBombs(nowMillis);
		recentNotifications.prune(nowMillis);
		recentProcessedMessages.prune(nowMillis);

		tickCounter++;
		chatBackfillTickCounter++;
		snapshotHeartbeatTickCounter++;

		if (tickCounter >= WORLD_POLL_INTERVAL_TICKS) {
			tickCounter = 0;
			resolveCurrentWorldName(client).ifPresent(worldName -> currentWorldName = worldName);
			changed |= scanBossBars(client, nowMillis);
		}

		if (chatBackfillTickCounter >= CHAT_BACKFILL_INTERVAL_TICKS) {
			chatBackfillTickCounter = 0;
			scanRecentChatHudMessages(client);
		}

		if (changed) {
			scheduleSnapshotPublish(false, nowMillis);
		}

		if (snapshotHeartbeatTickCounter >= SNAPSHOT_HEARTBEAT_INTERVAL_TICKS) {
			snapshotHeartbeatTickCounter = 0;
			scheduleSnapshotPublish(true, nowMillis);
		}
	}

	private Optional<String> resolveCurrentWorldName(MinecraftClient client) {
		if (client.getNetworkHandler() == null) {
			return Optional.empty();
		}

		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(BombMessageParser.WORLD_NAME_UUID);
		if (entry == null || entry.getDisplayName() == null) {
			return Optional.empty();
		}

		return parser.parseCurrentWorldName(entry.getDisplayName().getString());
	}

	private boolean scanBossBars(MinecraftClient client, long nowMillis) {
		if (currentWorldName.isBlank() || client.inGameHud == null) {
			return false;
		}

		BossBarHud bossBarHud = client.inGameHud.getBossBarHud();
		Map<UUID, ClientBossBar> bossBars = ((BossBarHudAccessor) bossBarHud).bombbellAnnouncer$getBossBars();
		Map<BombType, BombInfo> observedBombs = new EnumMap<>(BombType.class);

		for (ClientBossBar bossBar : bossBars.values()) {
			parser.parseBossBar(bossBar.getName().getString()).ifPresent(match -> {
				observedBombs.put(match.bombType(), new BombInfo(
					match.user(),
					match.bombType(),
					currentWorldName,
					nowMillis,
					match.lengthMinutes(),
					BombSource.LOCAL_BOSSBAR
				));
			});
		}

		boolean changed = false;
		for (BombInfo observedBomb : observedBombs.values()) {
			currentServerBombs.put(observedBomb.bombType(), observedBomb);
			changed |= activeBombs.upsertLocalBossBar(observedBomb);
		}

		List<BombType> missingBombs = currentServerBombs.keySet().stream()
			.filter(bombType -> !observedBombs.containsKey(bombType))
			.toList();

		for (BombType bombType : missingBombs) {
			BombInfo removed = currentServerBombs.remove(bombType);
			if (removed != null) {
				changed |= activeBombs.removeIfEquals(removed);
			}
		}

		return changed;
	}

	private void onWorldChange() {
		long nowMillis = System.currentTimeMillis();
		boolean changed = false;
		for (BombInfo bombInfo : currentServerBombs.values()) {
			changed |= activeBombs.removeIfEquals(bombInfo);
		}

		currentWorldName = "";
		tickCounter = 0;
		chatBackfillTickCounter = 0;
		snapshotHeartbeatTickCounter = 0;
		lastScannedChatCreationTick = Integer.MIN_VALUE;
		currentServerBombs.clear();

		if (changed) {
			scheduleSnapshotPublish(false, nowMillis);
		}
	}

	private void scanRecentChatHudMessages(MinecraftClient client) {
		if (client.inGameHud == null) {
			return;
		}

		ChatHud chatHud = client.inGameHud.getChatHud();
		List<ChatHudLine> messages = ((ChatHudAccessor) chatHud).bombbellAnnouncer$getMessages();

		int newestCreationTick = lastScannedChatCreationTick;
		for (int index = messages.size() - 1; index >= 0; index--) {
			ChatHudLine message = messages.get(index);
			int creationTick = message.creationTick();

			if (creationTick <= lastScannedChatCreationTick) {
				continue;
			}

			newestCreationTick = Math.max(newestCreationTick, creationTick);
			handleIncomingMessage(message.content());
		}

		lastScannedChatCreationTick = newestCreationTick;
	}

	private boolean pruneExpiredBombs(long nowMillis) {
		boolean changed = activeBombs.removeIf(bombInfo -> !bombInfo.isActive(nowMillis));

		List<BombType> expiredCurrentBombs = currentServerBombs.entrySet().stream()
			.filter(entry -> !entry.getValue().isActive(nowMillis))
			.map(Map.Entry::getKey)
			.toList();

		for (BombType bombType : expiredCurrentBombs) {
			currentServerBombs.remove(bombType);
		}

		return changed;
	}

	private void scheduleSnapshotPublish(boolean forceHeartbeat, long nowMillis) {
		evaluateSubscriptions(nowMillis);
		observationPublisher.requestPublish(getActiveBombsSnapshot(nowMillis), forceHeartbeat);
	}

	private void evaluateSubscriptions(long nowMillis) {
		List<SubscriptionMatch> currentMatches = subscriptionEvaluator.findMatches(
			getActiveBombsSnapshot(nowMillis),
			config.subscribedBombTypes().stream().map(com.bombbellannouncer.subscription.BombSubscription::new).toList(),
			config.subscribedCombos(),
			nowMillis
		);

		for (SubscriptionMatch match : subscriptionNotificationState.collectNewMatches(currentMatches)) {
			sendSubscriptionNotification(match, nowMillis);
		}
	}

	private void sendSubscriptionNotification(SubscriptionMatch match, long nowMillis) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}

		String world = match.world();
		String remaining = formatRemainingTime(match.remainingMillis(nowMillis));
		MutableText message = Text.literal("WynnData: ")
			.formatted(Formatting.AQUA)
			.append(Text.literal(buildSubscriptionSummary(match)))
			.append(Text.literal(" on " + world + ", " + remaining + " left. ").formatted(Formatting.WHITE))
			.append(Text.literal("[Switch]")
				.setStyle(Style.EMPTY
					.withColor(Formatting.GREEN)
					.withUnderline(true)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/switch " + world.toLowerCase(Locale.ROOT)))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/switch " + world.toLowerCase(Locale.ROOT))))));
		client.player.sendMessage(message, false);
	}

	private static String buildSubscriptionSummary(SubscriptionMatch match) {
		if (match.target() instanceof com.bombbellannouncer.subscription.BombSubscription) {
			BombInfo bombInfo = match.bombs().getFirst();
			return match.target().displayName() + " by " + bombInfo.user() + " active";
		}

		String details = match.bombs().stream()
			.map(bombInfo -> bombInfo.bombType().displayName() + ": " + bombInfo.user())
			.reduce((left, right) -> left + ", " + right)
			.orElse("active");
		return match.target().displayName() + " active (" + details + ")";
	}

	private static String formatRemainingTime(long remainingMillis) {
		long totalSeconds = Math.max(1L, Math.round(remainingMillis / 1_000.0d));
		if (totalSeconds < 60L) {
			return totalSeconds + "s";
		}

		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		if (seconds == 0L) {
			return minutes + "m";
		}
		return minutes + "m " + seconds + "s";
	}

	private static final class ActiveBombContainer {
		private final Map<BombKey, BombInfo> bombs = new ConcurrentHashMap<>();

		private boolean put(BombInfo bombInfo, boolean replaceIfExists) {
			BombKey key = new BombKey(bombInfo.server(), bombInfo.bombType());
			BombInfo existing = bombs.get(key);

			if (existing != null && !replaceIfExists) {
				return false;
			}

			if (bombInfo.equals(existing)) {
				return false;
			}

			bombs.put(key, bombInfo);
			return true;
		}

		public boolean add(BombInfo bombInfo) {
			return put(bombInfo, false);
		}

		public boolean forceAdd(BombInfo bombInfo) {
			return put(bombInfo, true);
		}

		public boolean upsertLocalBossBar(BombInfo bombInfo) {
			BombKey key = new BombKey(bombInfo.server(), bombInfo.bombType());
			BombInfo existing = bombs.get(key);

			if (existing != null && existing.source() != BombSource.LOCAL_BOSSBAR) {
				return false;
			}

			if (bombInfo.equals(existing)) {
				return false;
			}

			bombs.put(key, bombInfo);
			return true;
		}

		public boolean removeIfEquals(BombInfo bombInfo) {
			return bombs.remove(new BombKey(bombInfo.server(), bombInfo.bombType()), bombInfo);
		}

		public boolean removeIf(java.util.function.Predicate<BombInfo> predicate) {
			return bombs.entrySet().removeIf(entry -> predicate.test(entry.getValue()));
		}

		public Collection<BombInfo> asCollection() {
			return java.util.List.copyOf(bombs.values());
		}
	}
}
