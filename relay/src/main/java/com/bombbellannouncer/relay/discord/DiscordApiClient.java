package com.bombbellannouncer.relay.discord;

import com.bombbellannouncer.protocol.BombType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;

public final class DiscordApiClient {
	private static final String API_BASE_URL = "https://discord.com/api/v10";
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private final Logger logger;
	private final String botToken;
	private final String applicationId;

	public DiscordApiClient(Logger logger, String botToken, String applicationId) {
		this.logger = logger;
		this.botToken = botToken;
		this.applicationId = applicationId;
	}

	public DiscordApiResponse registerGlobalCommands() {
		JsonArray commands = new JsonArray();
		JsonObject command = new JsonObject();
		command.addProperty("name", "bombbell");
		command.addProperty("description", "Configure the Bombbell relay dashboard");

		JsonArray options = new JsonArray();
		options.add(buildSubcommand("setup", "Bind a dashboard channel", channelOption("channel", "Dashboard channel")));
		options.add(buildSubcommand("enroll", "Generate a private enrollment bundle"));
		options.add(buildSubcommand("disconnect", "Revoke your contributor sessions"));
		options.add(buildSubcommand("revoke", "Revoke a contributor", userOption("user", "Contributor to revoke")));
		options.add(buildSubcommandGroup("type", "Manage raw bomb type dashboard blocks",
			buildSubcommand("enable", "Enable a bomb type", bombTypeOption("bomb", "Bomb type", true)),
			buildSubcommand("disable", "Disable a bomb type", bombTypeOption("bomb", "Bomb type", true)),
			buildSubcommand("move", "Reorder a bomb type", bombTypeOption("bomb", "Bomb type", true), integerOption("position", "Target position", true)),
			buildSubcommand("list", "Show raw bomb type dashboard settings")
		));
		options.add(buildSubcommandGroup("combo", "Manage saved combination dashboard blocks",
			buildSubcommand("add", "Add a saved combo", stringOption("name", "Combo name", true), stringOption("bombs", "Comma-separated bomb types", true), sortModeOption("sort", "Combo sort", false)),
			buildSubcommand("edit", "Edit a saved combo", stringOption("name", "Combo name", true), stringOption("bombs", "Comma-separated bomb types", false), sortModeOption("sort", "Combo sort", false)),
			buildSubcommand("remove", "Remove a saved combo", stringOption("name", "Combo name", true)),
			buildSubcommand("move", "Reorder a saved combo", stringOption("name", "Combo name", true), integerOption("position", "Target position", true)),
			buildSubcommand("list", "Show saved combos")
		));

		command.add("options", options);
		commands.add(command);

		return sendRequest(commandsUri(), "PUT", commands.toString());
	}

	public DiscordApiResponse createMessage(String channelId, String payloadJson) {
		return sendRequest(channelMessagesUri(channelId), "POST", payloadJson);
	}

	public DiscordApiResponse editMessage(String channelId, String messageId, String payloadJson) {
		return sendRequest(channelMessageUri(channelId, messageId), "PATCH", payloadJson);
	}

	public DiscordApiResponse deleteMessage(String channelId, String messageId) {
		return sendRequest(channelMessageUri(channelId, messageId), "DELETE", "");
	}

	private DiscordApiResponse sendRequest(URI uri, String method, String payloadJson) {
		HttpRequest request = HttpRequest.newBuilder(uri)
			.header("Authorization", "Bot " + botToken)
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.method(method, HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
			.build();

		try {
			HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			String bodyText = response.body() == null ? "" : response.body();
			return new DiscordApiResponse(
				response.statusCode(),
				bodyText,
				parseMessageId(bodyText).orElse(""),
				parseRetryAfterMillis(bodyText, response.headers()).orElse(0L)
			);
		} catch (IOException exception) {
			logger.warn("Failed to call Discord API {}", uri, exception);
			return new DiscordApiResponse(0, exception.getMessage(), "", 0L);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			logger.warn("Interrupted while calling Discord API {}", uri, exception);
			return new DiscordApiResponse(0, exception.getMessage(), "", 0L);
		}
	}

	private URI commandsUri() {
		return URI.create(API_BASE_URL + "/applications/" + applicationId + "/commands");
	}

	private static URI channelMessagesUri(String channelId) {
		return URI.create(API_BASE_URL + "/channels/" + channelId + "/messages");
	}

	private static URI channelMessageUri(String channelId, String messageId) {
		return URI.create(API_BASE_URL + "/channels/" + channelId + "/messages/" + messageId);
	}

	private static JsonObject buildSubcommand(String name, String description, JsonObject... nestedOptions) {
		JsonObject option = new JsonObject();
		option.addProperty("type", 1);
		option.addProperty("name", name);
		option.addProperty("description", description);
		if (nestedOptions.length > 0) {
			JsonArray options = new JsonArray();
			for (JsonObject nestedOption : nestedOptions) {
				options.add(nestedOption);
			}
			option.add("options", options);
		}
		return option;
	}

	private static JsonObject buildSubcommandGroup(String name, String description, JsonObject... nestedOptions) {
		JsonObject option = new JsonObject();
		option.addProperty("type", 2);
		option.addProperty("name", name);
		option.addProperty("description", description);
		JsonArray options = new JsonArray();
		for (JsonObject nestedOption : nestedOptions) {
			options.add(nestedOption);
		}
		option.add("options", options);
		return option;
	}

	private static JsonObject channelOption(String name, String description) {
		JsonObject option = new JsonObject();
		option.addProperty("type", 7);
		option.addProperty("name", name);
		option.addProperty("description", description);
		option.addProperty("required", true);
		return option;
	}

	private static JsonObject userOption(String name, String description) {
		return typedOption(6, name, description, true);
	}

	private static JsonObject stringOption(String name, String description, boolean required) {
		return typedOption(3, name, description, required);
	}

	private static JsonObject integerOption(String name, String description, boolean required) {
		return typedOption(4, name, description, required);
	}

	private static JsonObject bombTypeOption(String name, String description, boolean required) {
		JsonObject option = new JsonObject();
		option.addProperty("type", 3);
		option.addProperty("name", name);
		option.addProperty("description", description);
		option.addProperty("required", required);
		JsonArray choices = new JsonArray();
		for (BombType bombType : BombType.values()) {
			JsonObject choice = new JsonObject();
			choice.addProperty("name", bombType.displayName());
			choice.addProperty("value", bombType.name());
			choices.add(choice);
		}
		option.add("choices", choices);
		return option;
	}

	private static JsonObject sortModeOption(String name, String description, boolean required) {
		JsonObject option = typedOption(3, name, description, required);
		JsonArray choices = new JsonArray();
		for (DashboardComboSortMode sortMode : DashboardComboSortMode.values()) {
			JsonObject choice = new JsonObject();
			choice.addProperty("name", sortMode.name());
			choice.addProperty("value", sortMode.name());
			choices.add(choice);
		}
		option.add("choices", choices);
		return option;
	}

	private static JsonObject typedOption(int type, String name, String description, boolean required) {
		JsonObject option = new JsonObject();
		option.addProperty("type", type);
		option.addProperty("name", name);
		option.addProperty("description", description);
		option.addProperty("required", required);
		return option;
	}

	private static Optional<String> parseMessageId(String bodyText) {
		if (bodyText == null || bodyText.isBlank()) {
			return Optional.empty();
		}

		try {
			JsonElement element = JsonParser.parseString(bodyText);
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				if (object.has("id")) {
					String messageId = object.get("id").getAsString().trim();
					return messageId.isBlank() ? Optional.empty() : Optional.of(messageId);
				}
			}
		} catch (RuntimeException ignored) {
		}

		return Optional.empty();
	}

	private static Optional<Long> parseRetryAfterMillis(String bodyText, HttpHeaders headers) {
		try {
			JsonElement element = JsonParser.parseString(bodyText);
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				if (object.has("retry_after")) {
					double retryAfterSeconds = object.get("retry_after").getAsDouble();
					return Optional.of(Math.max(1L, Math.round(retryAfterSeconds * 1_000.0d)));
				}
			}
		} catch (RuntimeException ignored) {
		}

		Optional<String> header = headers.firstValue("Retry-After");
		if (header.isEmpty()) {
			return Optional.empty();
		}

		try {
			double retryAfterSeconds = Double.parseDouble(header.get());
			return Optional.of(Math.max(1L, Math.round(retryAfterSeconds * 1_000.0d)));
		} catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	public record DiscordApiResponse(int statusCode, String body, String messageId, long retryAfterMillis) {
		public boolean isSuccess() {
			return statusCode >= 200 && statusCode < 300;
		}

		public boolean isUnauthorized() {
			return statusCode == 401 || statusCode == 403;
		}

		public boolean isNotFound() {
			return statusCode == 404;
		}

		public boolean isRateLimited() {
			return statusCode == 429;
		}
	}
}
