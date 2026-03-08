package com.bombbellannouncer.config;

import com.bombbellannouncer.bomb.BombType;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.subscription.ComboSubscription;
import com.bombbellannouncer.subscription.SubscriptionParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

public final class BombbellAnnouncerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE_NAME = "wynndata.json";
	private static final String LEGACY_FILE_NAME = "bombbell-announcer.json";

	private final Path configPath;
	private final Logger logger;
	private boolean enabled;
	private String relayBaseUrl;
	private String projectId;
	private String contributorToken;
	private String linkedDiscordUser;
	private String dashboardName;
	private ReporterRole reporterRole;
	private final Set<BombType> subscribedBombTypes;
	private final List<ComboSubscription> subscribedCombos;
	private long revision;

	private BombbellAnnouncerConfig(
		Path configPath,
		Logger logger,
		boolean enabled,
		String relayBaseUrl,
		String projectId,
		String contributorToken,
		String linkedDiscordUser,
		String dashboardName,
		ReporterRole reporterRole,
		Set<BombType> subscribedBombTypes,
		List<ComboSubscription> subscribedCombos
	) {
		this.configPath = configPath;
		this.logger = logger;
		this.enabled = enabled;
		this.relayBaseUrl = normalizeBaseUrl(relayBaseUrl);
		this.projectId = sanitize(projectId);
		this.contributorToken = sanitize(contributorToken);
		this.linkedDiscordUser = sanitize(linkedDiscordUser);
		this.dashboardName = sanitize(dashboardName);
		this.reporterRole = reporterRole == null ? ReporterRole.INELIGIBLE : reporterRole;
		this.subscribedBombTypes = subscribedBombTypes == null || subscribedBombTypes.isEmpty()
			? EnumSet.noneOf(BombType.class)
			: EnumSet.copyOf(subscribedBombTypes);
		this.subscribedCombos = subscribedCombos == null ? new ArrayList<>() : new ArrayList<>(subscribedCombos);
		this.subscribedCombos.sort(Comparator.comparing(ComboSubscription::encoded));
	}

	public static BombbellAnnouncerConfig load(Path configDirectory, Logger logger) {
		Path configPath = resolveConfigPath(configDirectory, logger);

		if (Files.notExists(configPath)) {
			writeDefaultConfig(configPath, logger);
			return emptyConfig(configPath, logger);
		}

		try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
			ConfigFile file = GSON.fromJson(reader, ConfigFile.class);
			return sanitize(configPath, logger, file);
		} catch (IOException | JsonParseException exception) {
			logger.warn("Failed to read config from {}", configPath, exception);
			return emptyConfig(configPath, logger);
		}
	}

	public synchronized boolean enabled() {
		return enabled;
	}

	public synchronized void setEnabled(boolean enabled) {
		if (this.enabled != enabled) {
			this.enabled = enabled;
			revision++;
		}
	}

	public synchronized String relayBaseUrl() {
		return relayBaseUrl;
	}

	public synchronized void setRelayBaseUrl(String relayBaseUrl) {
		String sanitized = normalizeBaseUrl(relayBaseUrl);
		if (!this.relayBaseUrl.equals(sanitized)) {
			this.relayBaseUrl = sanitized;
			revision++;
		}
	}

	public synchronized String projectId() {
		return projectId;
	}

	public synchronized void setProjectId(String projectId) {
		String sanitized = sanitize(projectId);
		if (!this.projectId.equals(sanitized)) {
			this.projectId = sanitized;
			revision++;
		}
	}

	public synchronized String contributorToken() {
		return contributorToken;
	}

	public synchronized void setContributorToken(String contributorToken) {
		String sanitized = sanitize(contributorToken);
		if (!this.contributorToken.equals(sanitized)) {
			this.contributorToken = sanitized;
			revision++;
		}
	}

	public synchronized String linkedDiscordUser() {
		return linkedDiscordUser;
	}

	public synchronized void setLinkedDiscordUser(String linkedDiscordUser) {
		String sanitized = sanitize(linkedDiscordUser);
		if (!this.linkedDiscordUser.equals(sanitized)) {
			this.linkedDiscordUser = sanitized;
			revision++;
		}
	}

	public synchronized String dashboardName() {
		return dashboardName;
	}

	public synchronized void setDashboardName(String dashboardName) {
		String sanitized = sanitize(dashboardName);
		if (!this.dashboardName.equals(sanitized)) {
			this.dashboardName = sanitized;
			revision++;
		}
	}

	public synchronized ReporterRole reporterRole() {
		return reporterRole;
	}

	public synchronized void setReporterRole(ReporterRole reporterRole) {
		ReporterRole normalized = reporterRole == null ? ReporterRole.INELIGIBLE : reporterRole;
		if (this.reporterRole != normalized) {
			this.reporterRole = normalized;
			revision++;
		}
	}

	public synchronized boolean hasRelaySession() {
		return !relayBaseUrl.isBlank() && !projectId.isBlank() && !contributorToken.isBlank();
	}

	public synchronized void storeContributorSession(
		String relayBaseUrl,
		String projectId,
		String contributorToken,
		String linkedDiscordUser,
		String dashboardName
	) {
		if (setSessionState(
			normalizeBaseUrl(relayBaseUrl),
			sanitize(projectId),
			sanitize(contributorToken),
			sanitize(linkedDiscordUser),
			sanitize(dashboardName),
			ReporterRole.INELIGIBLE
		)) {
			revision++;
		}
	}

	public synchronized void clearContributorSession() {
		if (setSessionState("", "", "", "", "", ReporterRole.INELIGIBLE)) {
			revision++;
		}
	}

	public synchronized long revision() {
		return revision;
	}

	public synchronized Set<BombType> subscribedBombTypes() {
		return subscribedBombTypes.isEmpty() ? EnumSet.noneOf(BombType.class) : EnumSet.copyOf(subscribedBombTypes);
	}

	public synchronized List<ComboSubscription> subscribedCombos() {
		return List.copyOf(subscribedCombos);
	}

	public synchronized boolean subscribeBombType(BombType bombType) {
		if (subscribedBombTypes.add(bombType)) {
			revision++;
			return true;
		}
		return false;
	}

	public synchronized boolean unsubscribeBombType(BombType bombType) {
		if (subscribedBombTypes.remove(bombType)) {
			revision++;
			return true;
		}
		return false;
	}

	public synchronized boolean subscribeCombo(ComboSubscription comboSubscription) {
		if (subscribedCombos.contains(comboSubscription)) {
			return false;
		}
		subscribedCombos.add(comboSubscription);
		subscribedCombos.sort(Comparator.comparing(ComboSubscription::encoded));
		revision++;
		return true;
	}

	public synchronized boolean unsubscribeCombo(ComboSubscription comboSubscription) {
		if (subscribedCombos.remove(comboSubscription)) {
			revision++;
			return true;
		}
		return false;
	}

	public synchronized boolean clearSubscriptions() {
		if (subscribedBombTypes.isEmpty() && subscribedCombos.isEmpty()) {
			return false;
		}
		subscribedBombTypes.clear();
		subscribedCombos.clear();
		revision++;
		return true;
	}

	public synchronized void save() {
		try {
			Files.createDirectories(configPath.getParent());

			try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
				GSON.toJson(ConfigFile.from(this), writer);
			}
		} catch (IOException exception) {
			logger.warn("Failed to save config to {}", configPath, exception);
		}
	}

	private static void writeDefaultConfig(Path configPath, Logger logger) {
		try {
			Files.createDirectories(configPath.getParent());

			try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
				GSON.toJson(new ConfigFile(), writer);
			}

			logger.info("Created default config at {}", configPath);
		} catch (IOException exception) {
			logger.warn("Failed to write default config to {}", configPath, exception);
		}
	}

	private static Path resolveConfigPath(Path configDirectory, Logger logger) {
		Path configPath = configDirectory.resolve(FILE_NAME);
		Path legacyPath = configDirectory.resolve(LEGACY_FILE_NAME);
		if (Files.exists(configPath) || Files.notExists(legacyPath)) {
			return configPath;
		}

		try {
			Files.createDirectories(configPath.getParent());
			Files.move(legacyPath, configPath);
			logger.info("Migrated config from {} to {}", legacyPath, configPath);
			return configPath;
		} catch (IOException exception) {
			logger.warn("Failed to migrate config from {} to {}", legacyPath, configPath, exception);
			return legacyPath;
		}
	}

	private static BombbellAnnouncerConfig sanitize(Path configPath, Logger logger, ConfigFile file) {
		if (file == null) {
			return emptyConfig(configPath, logger);
		}

		return new BombbellAnnouncerConfig(
			configPath,
			logger,
			file.enabled,
			file.relayBaseUrl,
			file.projectId,
			file.contributorToken,
			file.linkedDiscordUser,
			file.dashboardName,
			ReporterRole.fromName(file.reporterRole).orElse(ReporterRole.INELIGIBLE),
			parseSubscribedBombTypes(file.subscribedBombTypes),
			parseSubscribedCombos(file.subscribedCombos)
		);
	}

	private static BombbellAnnouncerConfig emptyConfig(Path configPath, Logger logger) {
		return new BombbellAnnouncerConfig(
			configPath,
			logger,
			true,
			"",
			"",
			"",
			"",
			"",
			ReporterRole.INELIGIBLE,
			EnumSet.noneOf(BombType.class),
			List.of()
		);
	}

	private static String sanitize(String value) {
		return value == null ? "" : value.trim();
	}

	private static String normalizeBaseUrl(String value) {
		String sanitized = sanitize(value);
		if (sanitized.endsWith("/")) {
			return sanitized.substring(0, sanitized.length() - 1);
		}
		return sanitized;
	}

	private boolean setSessionState(
		String relayBaseUrl,
		String projectId,
		String contributorToken,
		String linkedDiscordUser,
		String dashboardName,
		ReporterRole reporterRole
	) {
		boolean changed = false;

		if (!this.relayBaseUrl.equals(relayBaseUrl)) {
			this.relayBaseUrl = relayBaseUrl;
			changed = true;
		}
		if (!this.projectId.equals(projectId)) {
			this.projectId = projectId;
			changed = true;
		}
		if (!this.contributorToken.equals(contributorToken)) {
			this.contributorToken = contributorToken;
			changed = true;
		}
		if (!this.linkedDiscordUser.equals(linkedDiscordUser)) {
			this.linkedDiscordUser = linkedDiscordUser;
			changed = true;
		}
		if (!this.dashboardName.equals(dashboardName)) {
			this.dashboardName = dashboardName;
			changed = true;
		}

		ReporterRole normalizedRole = reporterRole == null ? ReporterRole.INELIGIBLE : reporterRole;
		if (this.reporterRole != normalizedRole) {
			this.reporterRole = normalizedRole;
			changed = true;
		}

		return changed;
	}

	private static final class ConfigFile {
		boolean enabled = true;
		String relayBaseUrl = "";
		String projectId = "";
		String contributorToken = "";
		String linkedDiscordUser = "";
		String dashboardName = "";
		String reporterRole = ReporterRole.INELIGIBLE.name();
		List<String> subscribedBombTypes = new ArrayList<>();
		List<String> subscribedCombos = new ArrayList<>();

		static ConfigFile from(BombbellAnnouncerConfig config) {
			ConfigFile file = new ConfigFile();
			file.enabled = config.enabled;
			file.relayBaseUrl = config.relayBaseUrl;
			file.projectId = config.projectId;
			file.contributorToken = config.contributorToken;
			file.linkedDiscordUser = config.linkedDiscordUser;
			file.dashboardName = config.dashboardName;
			file.reporterRole = config.reporterRole.name();
			file.subscribedBombTypes = config.subscribedBombTypes.stream()
				.sorted(Comparator.comparingInt(Enum::ordinal))
				.map(BombType::name)
				.toList();
			file.subscribedCombos = config.subscribedCombos.stream()
				.sorted(Comparator.comparing(ComboSubscription::encoded))
				.map(ComboSubscription::encoded)
				.toList();
			return file;
		}
	}

	private static Set<BombType> parseSubscribedBombTypes(List<String> rawValues) {
		EnumSet<BombType> bombTypes = EnumSet.noneOf(BombType.class);
		if (rawValues == null) {
			return bombTypes;
		}
		for (String rawValue : rawValues) {
			try {
				bombTypes.add(SubscriptionParser.parseBombType(rawValue));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return bombTypes;
	}

	private static List<ComboSubscription> parseSubscribedCombos(List<String> rawValues) {
		List<ComboSubscription> combos = new ArrayList<>();
		if (rawValues == null) {
			return combos;
		}
		for (String rawValue : rawValues) {
			try {
				ComboSubscription comboSubscription = SubscriptionParser.parseEncodedCombo(rawValue);
				if (!combos.contains(comboSubscription)) {
					combos.add(comboSubscription);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		combos.sort(Comparator.comparing(ComboSubscription::encoded));
		return combos;
	}
}
