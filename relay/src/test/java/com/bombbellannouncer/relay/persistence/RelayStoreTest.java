package com.bombbellannouncer.relay.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.protocol.BombSnapshotItem;
import com.bombbellannouncer.protocol.BombSource;
import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.relay.discord.DashboardComboSortMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelayStoreTest {
	@Test
	void initializeCreatesMissingSqliteParentDirectories(@TempDir Path tempDir) {
		Path databasePath = tempDir.resolve("nested").resolve("relay.db");
		RelayStore store = new RelayStore("jdbc:sqlite:" + databasePath);

		store.initialize();

		assertTrue(Files.exists(databasePath.getParent()));
	}

	@Test
	void chatBellBeatsLocalBossbarForSameBomb(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		RelayStore.ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);

		long nowMillis = System.currentTimeMillis();
		store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.LOOT,
			"WC1",
			"Unknown",
			nowMillis - 60_000L,
			nowMillis + 60_000L,
			BombSource.LOCAL_BOSSBAR
		), nowMillis);

		store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.LOOT,
			"WC1",
			"ExamplePlayer",
			nowMillis - 120_000L,
			nowMillis + 120_000L,
			BombSource.CHAT_BELL
		), nowMillis);

		List<RelayStore.ActiveBombRecord> bombs = store.findActiveBombs(project.projectId(), nowMillis);
		assertEquals(1, bombs.size());
		assertEquals(BombSource.CHAT_BELL, bombs.getFirst().source());
		assertEquals("ExamplePlayer", bombs.getFirst().user());
	}

	@Test
	void laterExpiryWinsForSameSource(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		RelayStore.ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);

		long nowMillis = System.currentTimeMillis();
		store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.PROFESSION_XP,
			"WC5",
			"PlayerOne",
			nowMillis - 60_000L,
			nowMillis + 60_000L,
			BombSource.CHAT_BELL
		), nowMillis);

		store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.PROFESSION_XP,
			"WC5",
			"PlayerOne",
			nowMillis - 120_000L,
			nowMillis + 120_000L,
			BombSource.CHAT_BELL
		), nowMillis);

		assertEquals(nowMillis + 120_000L, store.findActiveBombs(project.projectId(), nowMillis).getFirst().expiresAtMillis());
	}

	@Test
	void persistsDashboardTypeAndComboSettings(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		RelayStore.ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);

		store.setDashboardBombTypeEnabled(project.projectId(), BombType.LOOT, false, 2_000L);
		store.moveDashboardBombType(project.projectId(), BombType.LOOT_CHEST, 1, 2_000L);
		store.addDashboardCombo(project.projectId(), "prof", "Profession Pair", List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), DashboardComboSortMode.WORLD, 2_000L);

		assertEquals(BombType.LOOT_CHEST, store.findDashboardBombTypeSettings(project.projectId()).getFirst().bombType());
		assertEquals(false, store.findDashboardBombTypeSettings(project.projectId()).stream()
			.filter(setting -> setting.bombType() == BombType.LOOT)
			.findFirst()
			.orElseThrow()
			.enabled());
		assertEquals("prof", store.findDashboardCombos(project.projectId()).getFirst().normalizedName());
	}

	@Test
	void sanitizesStoredUserAndContributorNames(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		RelayStore.ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);
		long nowMillis = System.currentTimeMillis();

		store.storeEnrollmentCode(project.projectId(), "user-1", "\uDBFF\uDFFC\uE001\uDB00\uDC06 ratched10", "hash-1", nowMillis + 60_000L, nowMillis);
		RelayStore.EnrollmentGrant grant = store.consumeEnrollmentCode(project.projectId(), "hash-1", nowMillis + 1_000L);
		assertEquals("ratched10", grant.discordUsername());

		assertTrue(store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.LOOT,
			"WC1",
			"\uDBFF\uDFFC\uE001\uDB00\uDC06 ChrisRis",
			nowMillis,
			nowMillis + 100_000L,
			BombSource.CHAT_BELL
		), nowMillis));

		assertEquals("ChrisRis", store.findActiveBombs(project.projectId(), nowMillis).getFirst().user());
	}

	@Test
	void sanitizesStoredServerNames(@TempDir Path tempDir) {
		RelayStore store = new RelayStore("jdbc:sqlite:" + tempDir.resolve("relay.db"));
		store.initialize();
		RelayStore.ProjectRecord project = store.upsertProjectBinding("guild-1", "channel-1", "#bombs", 1_000L);
		long nowMillis = System.currentTimeMillis();

		assertTrue(store.mergeBombObservation(project.projectId(), new BombSnapshotItem(
			BombType.PROFESSION_XP,
			"\uDBFF\uDFFC\uE001\uDB00\uDC06 EU26",
			"TheBeamXD",
			nowMillis,
			nowMillis + 120_000L,
			BombSource.CHAT_BELL
		), nowMillis));

		assertEquals("EU26", store.findActiveBombs(project.projectId(), nowMillis).getFirst().server());
	}
}
