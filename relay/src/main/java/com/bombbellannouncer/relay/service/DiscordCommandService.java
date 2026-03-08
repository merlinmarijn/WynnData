package com.bombbellannouncer.relay.service;

import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.protocol.EnrollmentBundle;
import com.bombbellannouncer.relay.config.RelayConfig;
import com.bombbellannouncer.relay.discord.DashboardComboSortMode;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardBombTypeSettingRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardComboRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.bombbellannouncer.relay.util.CryptoUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class DiscordCommandService {
	private static final BigInteger ADMINISTRATOR_BIT = BigInteger.valueOf(0x8L);

	private final RelayStore store;
	private final RelayConfig config;
	private final EnrollmentService enrollmentService;
	private final DashboardSyncService dashboardSyncService;

	public DiscordCommandService(
		RelayStore store,
		RelayConfig config,
		EnrollmentService enrollmentService,
		DashboardSyncService dashboardSyncService
	) {
		this.store = store;
		this.config = config;
		this.enrollmentService = enrollmentService;
		this.dashboardSyncService = dashboardSyncService;
	}

	public JsonObject handleInteraction(JsonObject interaction) {
		int interactionType = interaction.get("type").getAsInt();
		if (interactionType == 1) {
			JsonObject response = new JsonObject();
			response.addProperty("type", 1);
			return response;
		}

		if (interactionType != 2) {
			return ephemeralResponse("Unsupported Discord interaction type.");
		}

		String guildId = stringValue(interaction, "guild_id");
		if (guildId.isBlank()) {
			return ephemeralResponse("This command must be used inside a Discord server.");
		}

		JsonObject member = interaction.getAsJsonObject("member");
		JsonObject user = member == null ? null : member.getAsJsonObject("user");
		String discordUserId = stringValue(user, "id");
		String discordUsername = displayName(user);
		String permissions = stringValue(member, "permissions");

		JsonObject data = interaction.getAsJsonObject("data");
		JsonArray options = data == null ? new JsonArray() : data.getAsJsonArray("options");
		if (options == null || options.isEmpty()) {
			return ephemeralResponse("Missing subcommand.");
		}

		JsonObject topLevel = options.get(0).getAsJsonObject();
		String topLevelName = stringValue(topLevel, "name");
		long nowMillis = System.currentTimeMillis();

		return switch (topLevelName) {
			case "setup" -> handleSetup(guildId, permissions, data, topLevel, nowMillis);
			case "enroll" -> handleEnroll(guildId, discordUserId, discordUsername, nowMillis);
			case "disconnect" -> handleDisconnect(guildId, discordUserId, nowMillis);
			case "revoke" -> handleRevoke(guildId, permissions, topLevel, nowMillis);
			case "type" -> handleTypeGroup(guildId, permissions, topLevel, nowMillis);
			case "combo" -> handleComboGroup(guildId, permissions, topLevel, nowMillis);
			default -> ephemeralResponse("Unknown subcommand.");
		};
	}

	private JsonObject handleSetup(String guildId, String permissions, JsonObject data, JsonObject subcommand, long nowMillis) {
		if (!hasAdministrator(permissions)) {
			return ephemeralResponse("You need Administrator permission to run /bombbell setup.");
		}

		String channelId = optionValue(subcommand.getAsJsonArray("options"), "channel");
		if (channelId.isBlank()) {
			return ephemeralResponse("Choose a dashboard channel.");
		}

		String channelName = resolvedChannelName(data, channelId);
		String dashboardName = channelName.isBlank() ? "#" + channelId : "#" + channelName;
		ProjectRecord project = store.upsertProjectBinding(guildId, channelId, dashboardName, nowMillis);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Dashboard bound to <#" + channelId + ">. Project ID: `" + project.projectId() + "`");
	}

	private JsonObject handleEnroll(String guildId, String discordUserId, String discordUsername, long nowMillis) {
		ProjectRecord project = store.findProjectByGuildId(guildId);
		if (project == null) {
			return ephemeralResponse("This server is not configured yet. Run /bombbell setup first.");
		}

		EnrollmentBundle bundle = enrollmentService.issueEnrollmentBundle(config.baseUrl(), project, discordUserId, discordUsername, nowMillis);
		String encodedBundle = CryptoUtil.encodeSetupBundle(bundle);
		return ephemeralResponse(
			"Paste this into the mod:\n```text\n" + encodedBundle + "\n```\nExpires <t:" + (bundle.expiresAtMillis() / 1_000L) + ":R>."
		);
	}

	private JsonObject handleDisconnect(String guildId, String discordUserId, long nowMillis) {
		ProjectRecord project = store.findProjectByGuildId(guildId);
		if (project == null) {
			return ephemeralResponse("This server is not configured yet.");
		}

		store.revokeCredentialsForDiscordUser(project.projectId(), discordUserId, nowMillis);
		return ephemeralResponse("All contributor sessions for your Discord account were revoked.");
	}

	private JsonObject handleRevoke(String guildId, String permissions, JsonObject subcommand, long nowMillis) {
		if (!hasAdministrator(permissions)) {
			return ephemeralResponse("You need Administrator permission to run /bombbell revoke.");
		}

		ProjectRecord project = store.findProjectByGuildId(guildId);
		if (project == null) {
			return ephemeralResponse("This server is not configured yet.");
		}

		String targetUserId = optionValue(subcommand.getAsJsonArray("options"), "user");
		if (targetUserId.isBlank()) {
			return ephemeralResponse("Choose a contributor to revoke.");
		}

		enrollmentService.revokeContributor(project.projectId(), targetUserId, nowMillis);
		return ephemeralResponse("Contributor revoked.");
	}

	private JsonObject handleTypeGroup(String guildId, String permissions, JsonObject group, long nowMillis) {
		if (!hasAdministrator(permissions)) {
			return ephemeralResponse("You need Administrator permission to manage dashboard bomb types.");
		}

		ProjectRecord project = store.findProjectByGuildId(guildId);
		if (project == null) {
			return ephemeralResponse("This server is not configured yet. Run /bombbell setup first.");
		}

		JsonObject subcommand = firstOption(group.getAsJsonArray("options"));
		String action = stringValue(subcommand, "name");
		return switch (action) {
			case "enable" -> handleTypeEnableDisable(project, subcommand, true, nowMillis);
			case "disable" -> handleTypeEnableDisable(project, subcommand, false, nowMillis);
			case "move" -> handleTypeMove(project, subcommand, nowMillis);
			case "list" -> ephemeralResponse(buildTypeList(project.projectId()));
			default -> ephemeralResponse("Unknown type command.");
		};
	}

	private JsonObject handleComboGroup(String guildId, String permissions, JsonObject group, long nowMillis) {
		if (!hasAdministrator(permissions)) {
			return ephemeralResponse("You need Administrator permission to manage dashboard combos.");
		}

		ProjectRecord project = store.findProjectByGuildId(guildId);
		if (project == null) {
			return ephemeralResponse("This server is not configured yet. Run /bombbell setup first.");
		}

		JsonObject subcommand = firstOption(group.getAsJsonArray("options"));
		String action = stringValue(subcommand, "name");
		return switch (action) {
			case "add" -> handleComboAdd(project, subcommand, nowMillis);
			case "edit" -> handleComboEdit(project, subcommand, nowMillis);
			case "remove" -> handleComboRemove(project, subcommand, nowMillis);
			case "move" -> handleComboMove(project, subcommand, nowMillis);
			case "list" -> ephemeralResponse(buildComboList(project.projectId()));
			default -> ephemeralResponse("Unknown combo command.");
		};
	}

	private JsonObject handleTypeEnableDisable(ProjectRecord project, JsonObject subcommand, boolean enabled, long nowMillis) {
		BombType bombType = parseBombType(optionValue(subcommand.getAsJsonArray("options"), "bomb")).orElse(null);
		if (bombType == null) {
			return ephemeralResponse("Choose a valid bomb type.");
		}

		store.setDashboardBombTypeEnabled(project.projectId(), bombType, enabled, nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse((enabled ? "Enabled " : "Disabled ") + bombType.displayName() + " in the dashboard.");
	}

	private JsonObject handleTypeMove(ProjectRecord project, JsonObject subcommand, long nowMillis) {
		BombType bombType = parseBombType(optionValue(subcommand.getAsJsonArray("options"), "bomb")).orElse(null);
		int position = integerOptionValue(subcommand.getAsJsonArray("options"), "position");
		if (bombType == null || position <= 0 || position > BombType.values().length) {
			return ephemeralResponse("Choose a valid bomb type and position.");
		}

		store.moveDashboardBombType(project.projectId(), bombType, position, nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Moved " + bombType.displayName() + " to position " + position + ".");
	}

	private JsonObject handleComboAdd(ProjectRecord project, JsonObject subcommand, long nowMillis) {
		String displayName = normalizeDisplayName(optionValue(subcommand.getAsJsonArray("options"), "name"));
		String normalizedName = normalizeComboKey(displayName);
		if (displayName.isBlank()) {
			return ephemeralResponse("Give the combo a name.");
		}
		if (store.findDashboardCombo(project.projectId(), normalizedName) != null) {
			return ephemeralResponse("A combo with that name already exists.");
		}

		ParsedComboInput parsed = parseComboInput(
			optionValue(subcommand.getAsJsonArray("options"), "bombs"),
			optionValue(subcommand.getAsJsonArray("options"), "sort"),
			true
		);
		if (!parsed.valid()) {
			return ephemeralResponse(parsed.error());
		}

		store.addDashboardCombo(project.projectId(), normalizedName, displayName, parsed.bombTypes(), parsed.sortMode(), nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Added combo `" + displayName + "`.");
	}

	private JsonObject handleComboEdit(ProjectRecord project, JsonObject subcommand, long nowMillis) {
		String normalizedName = normalizeComboKey(optionValue(subcommand.getAsJsonArray("options"), "name"));
		DashboardComboRecord existing = store.findDashboardCombo(project.projectId(), normalizedName);
		if (existing == null) {
			return ephemeralResponse("That combo does not exist.");
		}

		ParsedComboInput parsed = parseComboInput(
			optionValue(subcommand.getAsJsonArray("options"), "bombs"),
			optionValue(subcommand.getAsJsonArray("options"), "sort"),
			false
		);
		if (!parsed.valid()) {
			return ephemeralResponse(parsed.error());
		}

		List<BombType> bombTypes = parsed.bombTypes() == null ? existing.bombTypes() : parsed.bombTypes();
		DashboardComboSortMode sortMode = parsed.sortMode() == null ? existing.sortMode() : parsed.sortMode();
		store.updateDashboardCombo(project.projectId(), normalizedName, bombTypes, sortMode, nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Updated combo `" + existing.displayName() + "`.");
	}

	private JsonObject handleComboRemove(ProjectRecord project, JsonObject subcommand, long nowMillis) {
		String normalizedName = normalizeComboKey(optionValue(subcommand.getAsJsonArray("options"), "name"));
		DashboardComboRecord existing = store.findDashboardCombo(project.projectId(), normalizedName);
		if (existing == null) {
			return ephemeralResponse("That combo does not exist.");
		}

		store.removeDashboardCombo(project.projectId(), normalizedName, nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Removed combo `" + existing.displayName() + "`.");
	}

	private JsonObject handleComboMove(ProjectRecord project, JsonObject subcommand, long nowMillis) {
		String normalizedName = normalizeComboKey(optionValue(subcommand.getAsJsonArray("options"), "name"));
		int position = integerOptionValue(subcommand.getAsJsonArray("options"), "position");
		DashboardComboRecord existing = store.findDashboardCombo(project.projectId(), normalizedName);
		if (existing == null || position <= 0) {
			return ephemeralResponse("Choose a valid combo name and position.");
		}

		store.moveDashboardCombo(project.projectId(), normalizedName, position, nowMillis);
		store.setProjectDashboardLayoutVersion(project.projectId(), 0L);
		dashboardSyncService.requestSync(project.projectId(), true);
		return ephemeralResponse("Moved combo `" + existing.displayName() + "` to position " + position + ".");
	}

	private String buildTypeList(String projectId) {
		StringBuilder builder = new StringBuilder("Dashboard bomb types:\n");
		List<DashboardBombTypeSettingRecord> settings = store.findDashboardBombTypeSettings(projectId);
		for (int index = 0; index < settings.size(); index++) {
			DashboardBombTypeSettingRecord setting = settings.get(index);
			builder.append(index + 1)
				.append(". ")
				.append(setting.bombType().displayName())
				.append(" - ")
				.append(setting.enabled() ? "enabled" : "disabled")
				.append('\n');
		}
		return builder.toString().trim();
	}

	private String buildComboList(String projectId) {
		List<DashboardComboRecord> combos = store.findDashboardCombos(projectId);
		if (combos.isEmpty()) {
			return "No saved combos.";
		}

		StringBuilder builder = new StringBuilder("Saved combos:\n");
		for (int index = 0; index < combos.size(); index++) {
			DashboardComboRecord combo = combos.get(index);
			builder.append(index + 1)
				.append(". ")
				.append(combo.displayName())
				.append(" - ")
				.append(combo.sortMode().name())
				.append(" - ")
				.append(combo.bombTypes().stream().map(BombType::displayName).reduce((left, right) -> left + ", " + right).orElse(""))
				.append('\n');
		}
		return builder.toString().trim();
	}

	private ParsedComboInput parseComboInput(String bombsValue, String sortValue, boolean requireBombs) {
		List<BombType> bombTypes = null;
		if (!bombsValue.isBlank()) {
			LinkedHashSet<BombType> uniqueBombTypes = new LinkedHashSet<>();
			for (String token : bombsValue.split(",")) {
				Optional<BombType> bombType = parseBombType(token);
				if (bombType.isEmpty()) {
					return ParsedComboInput.invalid("Invalid bomb type list.");
				}
				if (!uniqueBombTypes.add(bombType.get())) {
					return ParsedComboInput.invalid("Duplicate bomb types are not allowed.");
				}
			}
			if (uniqueBombTypes.size() < 2) {
				return ParsedComboInput.invalid("A combo needs at least 2 unique bomb types.");
			}
			bombTypes = List.copyOf(uniqueBombTypes);
		} else if (requireBombs) {
			return ParsedComboInput.invalid("Provide a comma-separated bomb type list.");
		}

		DashboardComboSortMode sortMode = null;
		if (!sortValue.isBlank()) {
			sortMode = DashboardComboSortMode.fromName(sortValue).orElse(null);
			if (sortMode == null) {
				return ParsedComboInput.invalid("Invalid combo sort mode.");
			}
		} else if (requireBombs) {
			sortMode = DashboardComboSortMode.WORLD;
		}

		return ParsedComboInput.valid(bombTypes, sortMode);
	}

	private static Optional<BombType> parseBombType(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return Optional.empty();
		}

		String normalized = rawValue.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
		for (BombType bombType : BombType.values()) {
			String normalizedDisplayName = bombType.displayName().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
			if (bombType.name().equals(normalized) || normalizedDisplayName.equals(normalized)) {
				return Optional.of(bombType);
			}
		}
		return Optional.empty();
	}

	private static String normalizeComboKey(String rawValue) {
		if (rawValue == null) {
			return "";
		}
		return rawValue.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9_-]", "").toLowerCase(Locale.ROOT);
	}

	private static String normalizeDisplayName(String rawValue) {
		return rawValue == null ? "" : rawValue.trim().replaceAll("\\s+", " ");
	}

	private static JsonObject firstOption(JsonArray options) {
		return options == null || options.isEmpty() ? new JsonObject() : options.get(0).getAsJsonObject();
	}

	private static boolean hasAdministrator(String permissions) {
		if (permissions == null || permissions.isBlank()) {
			return false;
		}

		try {
			return new BigInteger(permissions).and(ADMINISTRATOR_BIT).compareTo(BigInteger.ZERO) > 0;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	private static JsonObject ephemeralResponse(String content) {
		JsonObject response = new JsonObject();
		response.addProperty("type", 4);

		JsonObject data = new JsonObject();
		data.addProperty("content", content);
		data.addProperty("flags", 64);
		response.add("data", data);
		return response;
	}

	private static String optionValue(JsonArray options, String optionName) {
		if (options == null) {
			return "";
		}
		for (JsonElement element : options) {
			JsonObject option = element.getAsJsonObject();
			if (optionName.equals(stringValue(option, "name")) && option.has("value")) {
				return option.get("value").getAsString();
			}
		}
		return "";
	}

	private static int integerOptionValue(JsonArray options, String optionName) {
		String rawValue = optionValue(options, optionName);
		if (rawValue.isBlank()) {
			return 0;
		}
		try {
			return Integer.parseInt(rawValue);
		} catch (NumberFormatException exception) {
			return 0;
		}
	}

	private static String resolvedChannelName(JsonObject data, String channelId) {
		JsonObject resolved = data.getAsJsonObject("resolved");
		if (resolved == null || !resolved.has("channels")) {
			return "";
		}

		JsonObject channels = resolved.getAsJsonObject("channels");
		if (!channels.has(channelId)) {
			return "";
		}

		JsonObject channel = channels.getAsJsonObject(channelId);
		return stringValue(channel, "name");
	}

	private static String displayName(JsonObject user) {
		String globalName = stringValue(user, "global_name");
		if (!globalName.isBlank()) {
			return globalName;
		}
		String username = stringValue(user, "username");
		if (!username.isBlank()) {
			return username;
		}
		return stringValue(user, "id");
	}

	private static String stringValue(JsonObject object, String field) {
		if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
			return "";
		}
		return object.get(field).getAsString();
	}

	private record ParsedComboInput(boolean valid, String error, List<BombType> bombTypes, DashboardComboSortMode sortMode) {
		private static ParsedComboInput valid(List<BombType> bombTypes, DashboardComboSortMode sortMode) {
			return new ParsedComboInput(true, "", bombTypes, sortMode);
		}

		private static ParsedComboInput invalid(String error) {
			return new ParsedComboInput(false, error, null, null);
		}
	}
}
