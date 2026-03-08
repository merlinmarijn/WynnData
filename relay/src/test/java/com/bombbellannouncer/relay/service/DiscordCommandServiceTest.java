package com.bombbellannouncer.relay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.relay.config.RelayConfig;
import com.bombbellannouncer.relay.discord.DashboardComboSortMode;
import com.bombbellannouncer.relay.discord.DiscordApiClient;
import com.bombbellannouncer.relay.discord.DiscordDashboardRenderer;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class DiscordCommandServiceTest {
	@Test
	void typeCommandsUpdateDashboardSettings(@TempDir Path tempDir) throws Exception {
		TestContext context = new TestContext(tempDir);

		JsonObject disableResponse = context.commandService.handleInteraction(context.groupInteraction(
			"type",
			context.subcommand("disable", context.option("bomb", BombType.LOOT.name()))
		));
		assertTrue(responseContent(disableResponse).contains("Disabled Loot"));
		assertEquals(false, context.store.findDashboardBombTypeSettings(context.project.projectId()).stream()
			.filter(setting -> setting.bombType() == BombType.LOOT)
			.findFirst()
			.orElseThrow()
			.enabled());

		JsonObject moveResponse = context.commandService.handleInteraction(context.groupInteraction(
			"type",
			context.subcommand("move", context.option("bomb", BombType.LOOT_CHEST.name()), context.option("position", 1))
		));
		assertTrue(responseContent(moveResponse).contains("Moved Loot Chest"));
		assertEquals(BombType.LOOT_CHEST, context.store.findDashboardBombTypeSettings(context.project.projectId()).getFirst().bombType());
	}

	@Test
	void comboCommandsValidateAndPersistSettings(@TempDir Path tempDir) throws Exception {
		TestContext context = new TestContext(tempDir);

		JsonObject invalidAddResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("add",
				context.option("name", "bad"),
				context.option("bombs", BombType.LOOT.name()),
				context.option("sort", DashboardComboSortMode.WORLD.name()))
		));
		assertTrue(responseContent(invalidAddResponse).contains("at least 2"));

		JsonObject addResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("add",
				context.option("name", "prof-pair"),
				context.option("bombs", BombType.PROFESSION_SPEED.name() + "," + BombType.PROFESSION_XP.name()),
				context.option("sort", DashboardComboSortMode.EARLIEST_EXPIRY.name()))
		));
		assertTrue(responseContent(addResponse).contains("Added combo"));
		assertEquals(1, context.store.findDashboardCombos(context.project.projectId()).size());

		JsonObject duplicateResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("add",
				context.option("name", "prof-pair"),
				context.option("bombs", BombType.PROFESSION_SPEED.name() + "," + BombType.PROFESSION_XP.name()),
				context.option("sort", DashboardComboSortMode.WORLD.name()))
		));
		assertTrue(responseContent(duplicateResponse).contains("already exists"));

		JsonObject editResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("edit",
				context.option("name", "prof-pair"),
				context.option("sort", DashboardComboSortMode.LATEST_EXPIRY.name()))
		));
		assertTrue(responseContent(editResponse).contains("Updated combo"));
		assertEquals(DashboardComboSortMode.LATEST_EXPIRY, context.store.findDashboardCombos(context.project.projectId()).getFirst().sortMode());
	}

	@Test
	void comboMoveAndRemoveCommandsReorderAndDelete(@TempDir Path tempDir) throws Exception {
		TestContext context = new TestContext(tempDir);
		context.store.addDashboardCombo(context.project.projectId(), "first", "First", java.util.List.of(BombType.LOOT, BombType.DUNGEON), DashboardComboSortMode.WORLD, 1_000L);
		context.store.addDashboardCombo(context.project.projectId(), "second", "Second", java.util.List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), DashboardComboSortMode.WORLD, 1_000L);

		JsonObject moveResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("move", context.option("name", "second"), context.option("position", 1))
		));
		assertTrue(responseContent(moveResponse).contains("Moved combo"));
		assertEquals("second", context.store.findDashboardCombos(context.project.projectId()).getFirst().normalizedName());

		JsonObject removeResponse = context.commandService.handleInteraction(context.groupInteraction(
			"combo",
			context.subcommand("remove", context.option("name", "second"))
		));
		assertTrue(responseContent(removeResponse).contains("Removed combo"));
		assertEquals(1, context.store.findDashboardCombos(context.project.projectId()).size());
		assertEquals("first", context.store.findDashboardCombos(context.project.projectId()).getFirst().normalizedName());
	}

	private static String responseContent(JsonObject response) {
		return response.getAsJsonObject("data").get("content").getAsString();
	}

	private static final class TestContext {
		private final RelayStore store;
		private final ProjectRecord project;
		private final DiscordCommandService commandService;

		private TestContext(Path tempDir) throws Exception {
			this.store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
			store.initialize();
			this.project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);
			EnrollmentService enrollmentService = new EnrollmentService(store, (projectId, force) -> {
			});
			DashboardSyncService dashboardSyncService = new DashboardSyncService(
				store,
				new DiscordApiClient(LoggerFactory.getLogger("test"), "token", "app"),
				new DiscordDashboardRenderer(),
				LoggerFactory.getLogger("test")
			);
			this.commandService = new DiscordCommandService(store, newConfig(), enrollmentService, dashboardSyncService);
		}

		private JsonObject groupInteraction(String groupName, JsonObject nestedSubcommand) {
			JsonObject interaction = baseInteraction();
			JsonObject data = interaction.getAsJsonObject("data");
			JsonArray options = new JsonArray();
			JsonObject group = new JsonObject();
			group.addProperty("name", groupName);
			group.add("options", jsonArray(nestedSubcommand));
			options.add(group);
			data.add("options", options);
			return interaction;
		}

		private JsonObject subcommand(String name, JsonObject... options) {
			JsonObject subcommand = new JsonObject();
			subcommand.addProperty("name", name);
			if (options.length > 0) {
				subcommand.add("options", jsonArray(options));
			}
			return subcommand;
		}

		private JsonObject option(String name, Object value) {
			JsonObject option = new JsonObject();
			option.addProperty("name", name);
			if (value instanceof Number number) {
				option.addProperty("value", number);
			} else {
				option.addProperty("value", String.valueOf(value));
			}
			return option;
		}

		private JsonObject baseInteraction() {
			JsonObject interaction = new JsonObject();
			interaction.addProperty("type", 2);
			interaction.addProperty("guild_id", "guild-1");
			JsonObject member = new JsonObject();
			member.addProperty("permissions", "8");
			JsonObject user = new JsonObject();
			user.addProperty("id", "user-1");
			user.addProperty("username", "Tester");
			member.add("user", user);
			interaction.add("member", member);
			interaction.add("data", new JsonObject());
			return interaction;
		}

		private static JsonArray jsonArray(JsonObject... objects) {
			JsonArray array = new JsonArray();
			for (JsonObject object : objects) {
				array.add(object);
			}
			return array;
		}

		private static RelayConfig newConfig() throws Exception {
			Constructor<RelayConfig> constructor = RelayConfig.class.getDeclaredConstructor(
				String.class,
				int.class,
				String.class,
				String.class,
				String.class,
				String.class,
				String.class
			);
			constructor.setAccessible(true);
			return constructor.newInstance("https://relay.example", 8080, "token", "app", "public", "jdbc:sqlite::memory:", "test");
		}
	}
}
