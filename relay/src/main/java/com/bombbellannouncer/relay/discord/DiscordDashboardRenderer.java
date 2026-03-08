package com.bombbellannouncer.relay.discord;

import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.relay.persistence.RelayStore.ActiveBombRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardBombTypeSettingRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardComboRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterChainSlotRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterDeviceRecord;
import com.bombbellannouncer.relay.text.DisplayTextSanitizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DiscordDashboardRenderer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public List<RenderedDashboardMessage> render(
		List<ActiveBombRecord> activeBombs,
		List<ReporterDeviceRecord> reporterDevices,
		List<ReporterChainSlotRecord> reporterChain,
		List<DashboardBombTypeSettingRecord> bombTypeSettings,
		List<DashboardComboRecord> combos
	) {
		List<RenderedDashboardMessage> rendered = new ArrayList<>();
		rendered.add(renderReporterStatus(reporterDevices, reporterChain));

		Map<BombType, List<ActiveBombRecord>> bombsByType = groupBombsByType(activeBombs);
		for (DashboardBombTypeSettingRecord setting : bombTypeSettings) {
			if (!setting.enabled()) {
				continue;
			}

			List<ActiveBombRecord> bombsForType = new ArrayList<>(bombsByType.getOrDefault(setting.bombType(), List.of()));
			bombsForType.sort(Comparator
				.comparingLong(ActiveBombRecord::expiresAtMillis)
				.thenComparing(ActiveBombRecord::server)
				.thenComparing(ActiveBombRecord::user));
			String description = bombsForType.isEmpty()
				? "No active bombs."
				: bombsForType.stream().map(DiscordDashboardRenderer::formatBombLine).reduce((left, right) -> left + "\n" + right).orElse("No active bombs.");
			String payloadJson = buildBombPayload(setting.bombType(), description);
			rendered.add(new RenderedDashboardMessage(DashboardSlotId.bomb(setting.bombType()), payloadJson, sha256(payloadJson)));
		}

		for (DashboardComboRecord combo : combos) {
			String payloadJson = buildComboPayload(combo, bombsByType, bombTypeSettings);
			rendered.add(new RenderedDashboardMessage(DashboardSlotId.combo(combo.normalizedName()), payloadJson, sha256(payloadJson)));
		}

		return rendered;
	}

	private static Map<BombType, List<ActiveBombRecord>> groupBombsByType(List<ActiveBombRecord> activeBombs) {
		Map<BombType, List<ActiveBombRecord>> grouped = new EnumMap<>(BombType.class);
		for (BombType bombType : BombType.values()) {
			grouped.put(bombType, new ArrayList<>());
		}
		for (ActiveBombRecord activeBomb : activeBombs) {
			grouped.get(activeBomb.bombType()).add(activeBomb);
		}
		return grouped;
	}

	private static RenderedDashboardMessage renderReporterStatus(List<ReporterDeviceRecord> reporterDevices, List<ReporterChainSlotRecord> reporterChain) {
		Map<Long, ReporterDeviceRecord> devicesByCredentialId = new HashMap<>();
		for (ReporterDeviceRecord reporterDevice : reporterDevices) {
			devicesByCredentialId.put(reporterDevice.credentialId(), reporterDevice);
		}

		Map<ReporterRole, ReporterChainSlotRecord> chainByRole = new EnumMap<>(ReporterRole.class);
		for (ReporterChainSlotRecord slot : reporterChain) {
			chainByRole.put(slot.role(), slot);
		}

		Set<Long> assignedCredentials = new HashSet<>();
		for (ReporterChainSlotRecord slot : reporterChain) {
			assignedCredentials.add(slot.credentialId());
		}

		List<ReporterDeviceRecord> waiting = reporterDevices.stream()
			.filter(ReporterDeviceRecord::eligible)
			.filter(device -> !assignedCredentials.contains(device.credentialId()))
			.sorted(Comparator
				.comparing((ReporterDeviceRecord device) -> device.eligibleAt() == null ? Long.MAX_VALUE : device.eligibleAt())
				.thenComparingLong(ReporterDeviceRecord::credentialId))
			.toList();
		List<ReporterDeviceRecord> ineligible = reporterDevices.stream()
			.filter(device -> !device.eligible())
			.sorted(Comparator
				.comparingLong(ReporterDeviceRecord::lastActiveAt)
				.reversed()
				.thenComparingLong(ReporterDeviceRecord::credentialId))
			.toList();

		String payloadJson = buildReporterStatusPayload(
			resolveAssignedReporter(chainByRole.get(ReporterRole.PRIMARY), devicesByCredentialId),
			resolveAssignedReporter(chainByRole.get(ReporterRole.SECONDARY), devicesByCredentialId),
			resolveAssignedReporter(chainByRole.get(ReporterRole.TERTIARY), devicesByCredentialId),
			waiting,
			ineligible
		);
		return new RenderedDashboardMessage(DashboardSlotId.REPORTER_STATUS, payloadJson, sha256(payloadJson));
	}

	private static String buildComboPayload(
		DashboardComboRecord combo,
		Map<BombType, List<ActiveBombRecord>> bombsByType,
		List<DashboardBombTypeSettingRecord> bombTypeSettings
	) {
		Set<BombType> disabledTypes = bombTypeSettings.stream()
			.filter(setting -> !setting.enabled())
			.map(DashboardBombTypeSettingRecord::bombType)
			.collect(java.util.stream.Collectors.toSet());

		boolean comboBlocked = combo.bombTypes().stream().anyMatch(disabledTypes::contains);
		List<String> worldLines = comboBlocked
			? List.of()
			: buildComboWorldLines(combo, bombsByType);

		String description = worldLines.isEmpty() ? "No matching worlds." : String.join("\n", worldLines);
		JsonObject root = new JsonObject();
		JsonArray embeds = new JsonArray();
		JsonObject embed = new JsonObject();
		embed.addProperty("title", sanitizeLabel(combo.displayName(), "Combination"));
		embed.addProperty("description", description);
		embed.addProperty("color", 0x334155);
		embeds.add(embed);
		root.add("embeds", embeds);
		root.add("allowed_mentions", emptyAllowedMentions());
		return GSON.toJson(root);
	}

	private static List<String> buildComboWorldLines(DashboardComboRecord combo, Map<BombType, List<ActiveBombRecord>> bombsByType) {
		Map<String, List<ActiveBombRecord>> matchesByWorld = new HashMap<>();
		List<ActiveBombRecord> firstBombs = bombsByType.getOrDefault(combo.bombTypes().getFirst(), List.of());
		for (ActiveBombRecord firstBomb : firstBombs) {
			boolean allPresent = true;
			List<ActiveBombRecord> matchedBombs = new ArrayList<>();
			matchedBombs.add(firstBomb);
			for (int index = 1; index < combo.bombTypes().size(); index++) {
				BombType requiredType = combo.bombTypes().get(index);
				ActiveBombRecord sameWorldBomb = bombsByType.getOrDefault(requiredType, List.of()).stream()
					.filter(bomb -> bomb.server().equals(firstBomb.server()))
					.max(Comparator.comparingLong(ActiveBombRecord::expiresAtMillis))
					.orElse(null);
				if (sameWorldBomb == null) {
					allPresent = false;
					break;
				}
				matchedBombs.add(sameWorldBomb);
			}
			if (allPresent) {
				matchesByWorld.put(firstBomb.server(), matchedBombs);
			}
		}

		Comparator<Map.Entry<String, List<ActiveBombRecord>>> order = switch (combo.sortMode()) {
			case WORLD -> Comparator.comparing(Map.Entry::getKey);
			case EARLIEST_EXPIRY -> Comparator
				.comparingLong((Map.Entry<String, List<ActiveBombRecord>> entry) -> entry.getValue().stream().mapToLong(ActiveBombRecord::expiresAtMillis).min().orElse(Long.MAX_VALUE))
				.thenComparing(Map.Entry::getKey);
			case LATEST_EXPIRY -> Comparator
				.comparingLong((Map.Entry<String, List<ActiveBombRecord>> entry) -> entry.getValue().stream().mapToLong(ActiveBombRecord::expiresAtMillis).max().orElse(Long.MIN_VALUE))
				.reversed()
				.thenComparing(Map.Entry::getKey);
		};

		return matchesByWorld.entrySet().stream()
			.sorted(order)
			.map(entry -> formatComboWorldLine(entry.getKey(), combo.bombTypes(), entry.getValue()))
			.toList();
	}

	private static String formatComboWorldLine(String world, List<BombType> bombTypes, List<ActiveBombRecord> bombs) {
		Map<BombType, ActiveBombRecord> bombsByType = new EnumMap<>(BombType.class);
		for (ActiveBombRecord bomb : bombs) {
			bombsByType.put(bomb.bombType(), bomb);
		}
		StringBuilder builder = new StringBuilder("- `").append(sanitizeLabel(world, "Unknown")).append("`");
		for (BombType bombType : bombTypes) {
			ActiveBombRecord bomb = bombsByType.get(bombType);
			if (bomb == null) {
				continue;
			}
			builder.append(" | ")
				.append(bombType.displayName())
				.append(": ")
				.append(sanitizeLabel(bomb.user(), "Unknown"))
				.append(" <t:")
				.append(bomb.expiresAtMillis() / 1_000L)
				.append(":R>");
		}
		return builder.toString();
	}

	private static String formatBombLine(ActiveBombRecord bomb) {
		return "- `" + sanitizeLabel(bomb.server(), "Unknown") + "` - " + sanitizeLabel(bomb.user(), "Unknown") + " - expires <t:" + (bomb.expiresAtMillis() / 1_000L) + ":R>";
	}

	private static String buildReporterStatusPayload(
		AssignedReporter primary,
		AssignedReporter secondary,
		AssignedReporter tertiary,
		List<ReporterDeviceRecord> waiting,
		List<ReporterDeviceRecord> ineligible
	) {
		JsonObject root = new JsonObject();
		JsonArray embeds = new JsonArray();
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "Reporter Status");
		embed.addProperty("description", "Snapshot submission priority always flows Primary -> Secondary -> Tertiary.");
		embed.addProperty("color", 0x4B5563);

		JsonArray fields = new JsonArray();
		fields.add(field("Primary", primary == null ? "None" : formatAssignedReporterLine(primary)));
		fields.add(field("Secondary", secondary == null ? "None" : formatAssignedReporterLine(secondary)));
		fields.add(field("Tertiary", tertiary == null ? "None" : formatAssignedReporterLine(tertiary)));
		fields.add(field("Waiting", waiting.isEmpty() ? "None" : joinReporterLines(waiting)));
		fields.add(field("Ineligible", ineligible.isEmpty() ? "None" : joinReporterLines(ineligible)));
		embed.add("fields", fields);

		embeds.add(embed);
		root.add("embeds", embeds);
		root.add("allowed_mentions", emptyAllowedMentions());
		return GSON.toJson(root);
	}

	private static String buildBombPayload(BombType bombType, String description) {
		JsonObject root = new JsonObject();
		JsonArray embeds = new JsonArray();
		JsonObject embed = new JsonObject();
		embed.addProperty("title", bombType.displayName());
		embed.addProperty("description", description);
		embed.addProperty("color", colorFor(bombType));
		embeds.add(embed);
		root.add("embeds", embeds);
		root.add("allowed_mentions", emptyAllowedMentions());
		return GSON.toJson(root);
	}

	private static JsonObject field(String name, String value) {
		JsonObject field = new JsonObject();
		field.addProperty("name", name);
		field.addProperty("value", value);
		field.addProperty("inline", false);
		return field;
	}

	private static String joinReporterLines(List<ReporterDeviceRecord> devices) {
		return devices.stream().map(DiscordDashboardRenderer::formatReporterLine).reduce((left, right) -> left + "\n" + right).orElse("None");
	}

	private static AssignedReporter resolveAssignedReporter(ReporterChainSlotRecord slot, Map<Long, ReporterDeviceRecord> devicesByCredentialId) {
		if (slot == null) {
			return null;
		}
		ReporterDeviceRecord reporterDevice = devicesByCredentialId.get(slot.credentialId());
		if (reporterDevice == null) {
			return null;
		}
		return new AssignedReporter(reporterDevice, slot.missCount());
	}

	private static String formatReporterLine(ReporterDeviceRecord device) {
		long lastSeenSeconds = Math.max(1L, device.lastActiveAt() / 1_000L);
		return "- " + sanitizeLabel(device.discordUsername(), "Unknown") + " [" + device.tokenPrefix() + "] - last seen <t:" + lastSeenSeconds + ":R>";
	}

	private static String formatAssignedReporterLine(AssignedReporter assignedReporter) {
		String base = formatReporterLine(assignedReporter.device());
		if (assignedReporter.missCount() <= 0) {
			return base;
		}
		return base + " - misses " + assignedReporter.missCount() + "/2";
	}

	private static JsonObject emptyAllowedMentions() {
		JsonObject allowedMentions = new JsonObject();
		allowedMentions.add("parse", new JsonArray());
		return allowedMentions;
	}

	private static int colorFor(BombType bombType) {
		return switch (bombType) {
			case COMBAT_XP -> 0xD97706;
			case DUNGEON -> 0xB91C1C;
			case LOOT -> 0x0F766E;
			case PROFESSION_SPEED -> 0x2563EB;
			case PROFESSION_XP -> 0x16A34A;
			case LOOT_CHEST -> 0x7C3AED;
		};
	}

	private static String sanitizeLabel(String value, String fallback) {
		return DisplayTextSanitizer.sanitizeName(value, fallback);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256 support", exception);
		}
	}

	public record RenderedDashboardMessage(String slotId, String payloadJson, String hash) {
	}

	private record AssignedReporter(ReporterDeviceRecord device, int missCount) {
	}
}
