package com.bombbellannouncer.command;

import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.subscription.BombSubscription;
import com.bombbellannouncer.subscription.ComboSubscription;
import com.bombbellannouncer.subscription.SubscriptionParser;
import com.bombbellannouncer.tracker.ActiveBombListView;
import com.bombbellannouncer.tracker.BombTrackerController;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class WynnDataClientCommands {
	private WynnDataClientCommands() {
	}

	public static void register(Supplier<BombTrackerController> trackerSupplier, BombbellAnnouncerConfig config) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(buildRoot(trackerSupplier, config)));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> buildRoot(
		Supplier<BombTrackerController> trackerSupplier,
		BombbellAnnouncerConfig config
	) {
		return ClientCommandManager.literal("wyndata")
			.then(ClientCommandManager.literal("bombs")
				.then(ClientCommandManager.literal("sub")
				.then(ClientCommandManager.literal("bomb")
					.then(ClientCommandManager.argument("bomb_type", StringArgumentType.greedyString())
						.executes(context -> execute(context, () -> {
							BombSubscription subscription = new BombSubscription(SubscriptionParser.parseBombType(StringArgumentType.getString(context, "bomb_type")));
							if (!config.subscribeBombType(subscription.bombType())) {
								context.getSource().sendFeedback(Text.literal("Already subscribed to " + subscription.displayName() + "."));
								return 0;
							}
							config.save();
							trackerSupplier.get().onSubscriptionAdded(subscription);
							context.getSource().sendFeedback(Text.literal("Subscribed to " + subscription.displayName() + "."));
							return 1;
						}))))
				.then(ClientCommandManager.literal("combo")
					.then(ClientCommandManager.argument("bomb_types", StringArgumentType.greedyString())
						.executes(context -> execute(context, () -> {
							ComboSubscription subscription = SubscriptionParser.parseCombo(StringArgumentType.getString(context, "bomb_types"));
							if (!config.subscribeCombo(subscription)) {
								context.getSource().sendFeedback(Text.literal("Already subscribed to combo " + subscription.displayName() + "."));
								return 0;
							}
							config.save();
							trackerSupplier.get().onSubscriptionAdded(subscription);
							context.getSource().sendFeedback(Text.literal("Subscribed to combo " + subscription.displayName() + "."));
							return 1;
						}))))
				)
				.then(ClientCommandManager.literal("unsub")
				.then(ClientCommandManager.literal("bomb")
					.then(ClientCommandManager.argument("bomb_type", StringArgumentType.greedyString())
						.executes(context -> execute(context, () -> {
							BombSubscription subscription = new BombSubscription(SubscriptionParser.parseBombType(StringArgumentType.getString(context, "bomb_type")));
							if (!config.unsubscribeBombType(subscription.bombType())) {
								context.getSource().sendFeedback(Text.literal("You are not subscribed to " + subscription.displayName() + "."));
								return 0;
							}
							config.save();
							trackerSupplier.get().onSubscriptionRemoved(subscription);
							context.getSource().sendFeedback(Text.literal("Unsubscribed from " + subscription.displayName() + "."));
							return 1;
						}))))
				.then(ClientCommandManager.literal("combo")
					.then(ClientCommandManager.argument("bomb_types", StringArgumentType.greedyString())
						.executes(context -> execute(context, () -> {
							ComboSubscription subscription = SubscriptionParser.parseCombo(StringArgumentType.getString(context, "bomb_types"));
							if (!config.unsubscribeCombo(subscription)) {
								context.getSource().sendFeedback(Text.literal("You are not subscribed to combo " + subscription.displayName() + "."));
								return 0;
							}
							config.save();
							trackerSupplier.get().onSubscriptionRemoved(subscription);
							context.getSource().sendFeedback(Text.literal("Unsubscribed from combo " + subscription.displayName() + "."));
							return 1;
						}))))
				)
				.then(ClientCommandManager.literal("subs")
				.executes(context -> {
					context.getSource().sendFeedback(Text.literal(buildSubscriptionList(config)));
					return 1;
				}))
				.then(ClientCommandManager.literal("list")
				.executes(context -> {
					sendActiveBombList(context.getSource(), trackerSupplier.get().describeActiveBombs(System.currentTimeMillis()));
					return 1;
				}))
				.then(ClientCommandManager.literal("clear")
				.executes(context -> {
					if (!config.clearSubscriptions()) {
						context.getSource().sendFeedback(Text.literal("No subscriptions to clear."));
						return 0;
					}
					config.save();
					trackerSupplier.get().onSubscriptionsCleared();
					context.getSource().sendFeedback(Text.literal("Cleared all subscriptions."));
					return 1;
				})));
	}

	private static String buildSubscriptionList(BombbellAnnouncerConfig config) {
		StringBuilder builder = new StringBuilder("Subscriptions:");
		if (config.subscribedBombTypes().isEmpty() && config.subscribedCombos().isEmpty()) {
			return builder.append("\n- none").toString();
		}

		for (var bombType : config.subscribedBombTypes()) {
			builder.append("\n- bomb: ").append(bombType.displayName());
		}
		for (var combo : config.subscribedCombos()) {
			builder.append("\n- combo: ").append(combo.displayName());
		}
		return builder.toString();
	}

	private static void sendActiveBombList(FabricClientCommandSource source, ActiveBombListView view) {
		if (view.isEmpty()) {
			source.sendFeedback(Text.literal("No active bombs tracked."));
			return;
		}

		if (!view.bombSections().isEmpty()) {
			source.sendFeedback(Text.literal("Bombs:").formatted(Formatting.GOLD));
			for (ActiveBombListView.Section section : view.bombSections()) {
				sendSection(source, section, false);
			}
		}

		if (!view.comboSections().isEmpty()) {
			source.sendFeedback(Text.literal("Subscribed combos:").formatted(Formatting.AQUA));
			for (ActiveBombListView.Section section : view.comboSections()) {
				sendSection(source, section, true);
			}
		}
	}

	private static void sendSection(FabricClientCommandSource source, ActiveBombListView.Section section, boolean allowEmpty) {
		source.sendFeedback(Text.literal(section.title() + ":").formatted(Formatting.YELLOW));
		if (section.entries().isEmpty()) {
			if (allowEmpty) {
				source.sendFeedback(Text.literal("  No matching worlds.").formatted(Formatting.DARK_GRAY));
			}
			return;
		}

		for (ActiveBombListView.Entry entry : section.entries()) {
			source.sendFeedback(buildEntryLine(entry));
		}
	}

	private static Text buildEntryLine(ActiveBombListView.Entry entry) {
		String lobbyCode = entry.lobbyCode();
		MutableText line = Text.literal("  " + entry.world() + " - " + formatRemainingTime(entry.remainingMillis()) + " ")
			.formatted(Formatting.WHITE);
		line.append(Text.literal("[Switch]")
			.setStyle(Style.EMPTY
				.withColor(Formatting.GREEN)
				.withUnderline(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/switch " + lobbyCode))
				.withInsertion("/switch " + lobbyCode)));
		return line;
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

	private static int execute(CommandContext<FabricClientCommandSource> context, CommandAction action) {
		try {
			return action.run();
		} catch (IllegalArgumentException exception) {
			context.getSource().sendFeedback(Text.literal(exception.getMessage()));
			return 0;
		}
	}

	@FunctionalInterface
	private interface CommandAction {
		int run();
	}
}
