package com.bombbellannouncer.relay.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

public final class RelayConfig {
	private final String baseUrl;
	private final int port;
	private final String discordBotToken;
	private final String discordApplicationId;
	private final String discordPublicKey;
	private final String jdbcUrl;
	private final String configSource;

	private RelayConfig(
		String baseUrl,
		int port,
		String discordBotToken,
		String discordApplicationId,
		String discordPublicKey,
		String jdbcUrl,
		String configSource
	) {
		this.baseUrl = baseUrl;
		this.port = port;
		this.discordBotToken = discordBotToken;
		this.discordApplicationId = discordApplicationId;
		this.discordPublicKey = discordPublicKey;
		this.jdbcUrl = jdbcUrl;
		this.configSource = configSource;
	}

	public static RelayConfig load() {
		Path envPath = resolveEnvPath();
		Map<String, String> envFile = envPath == null ? Map.of() : EnvFileLoader.load(envPath);
		String baseUrl = requiredValue("BASE_URL", envFile);
		String botToken = requiredValue("DISCORD_BOT_TOKEN", envFile);
		String applicationId = requiredValue("DISCORD_APPLICATION_ID", envFile);
		String publicKey = requiredValue("DISCORD_PUBLIC_KEY", envFile);
		String jdbcUrl = resolveJdbcUrl(envFile);
		int port = resolvePort(baseUrl, value("PORT", envFile));

		return new RelayConfig(
			normalizeBaseUrl(baseUrl),
			port,
			botToken,
			applicationId,
			publicKey,
			jdbcUrl,
			envPath == null ? "environment variables only" : envPath.toAbsolutePath().normalize().toString()
		);
	}

	public String baseUrl() {
		return baseUrl;
	}

	public int port() {
		return port;
	}

	public String discordBotToken() {
		return discordBotToken;
	}

	public String discordApplicationId() {
		return discordApplicationId;
	}

	public String discordPublicKey() {
		return discordPublicKey;
	}

	public String jdbcUrl() {
		return jdbcUrl;
	}

	public String configSource() {
		return configSource;
	}

	private static Path resolveEnvPath() {
		String explicitEnvPath = System.getenv("BOMBBELL_ENV_FILE");
		if (explicitEnvPath != null && !explicitEnvPath.isBlank()) {
			return Path.of(explicitEnvPath.trim());
		}

		return EnvFileLoader.firstExisting(
			Path.of("relay", ".env"),
			Path.of(".env")
		);
	}

	private static String requiredValue(String key, Map<String, String> envFile) {
		String value = value(key, envFile);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Missing required configuration: " + key);
		}
		return value.trim();
	}

	private static String value(String key, Map<String, String> envFile) {
		String env = System.getenv(key);
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		String fileValue = envFile.get(key);
		return fileValue == null ? "" : fileValue.trim();
	}

	private static int resolvePort(String baseUrl, String explicitPort) {
		if (explicitPort != null && !explicitPort.isBlank()) {
			return Integer.parseInt(explicitPort);
		}

		try {
			URI uri = new URI(baseUrl);
			if (uri.getPort() > 0) {
				return uri.getPort();
			}
			return 8080;
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("Invalid BASE_URL: " + baseUrl, exception);
		}
	}

	private static String resolveJdbcUrl(Map<String, String> envFile) {
		String explicitJdbcUrl = value("DATABASE_URL", envFile);
		if (!explicitJdbcUrl.isBlank()) {
			return explicitJdbcUrl;
		}

		String sqlitePath = value("SQLITE_PATH", envFile);
		if (sqlitePath.isBlank()) {
			sqlitePath = "./data/relay.db";
		}

		return "jdbc:sqlite:" + sqlitePath;
	}

	private static String normalizeBaseUrl(String value) {
		String normalized = value.trim();
		if (normalized.endsWith("/")) {
			return normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}
}
