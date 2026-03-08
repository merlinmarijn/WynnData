package com.bombbellannouncer.relay.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.protocol.BombSource;
import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.relay.persistence.RelayStore.ActiveBombRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardBombTypeSettingRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.DashboardComboRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterChainSlotRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterDeviceRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DiscordDashboardRendererTest {
	private final DiscordDashboardRenderer renderer = new DiscordDashboardRenderer();

	@Test
	void rendersOnlyEnabledBombTypesAndCombosInConfiguredOrder() {
		long nowMillis = System.currentTimeMillis();
		List<DiscordDashboardRenderer.RenderedDashboardMessage> rendered = renderer.render(
			List.of(
				new ActiveBombRecord("project-1", "WC9", BombType.COMBAT_XP, "ExamplePlayer", nowMillis - 60_000L, nowMillis + 120_000L, BombSource.CHAT_BELL),
				new ActiveBombRecord("project-1", "WC9", BombType.PROFESSION_SPEED, "SpeedUser", nowMillis - 30_000L, nowMillis + 90_000L, BombSource.CHAT_BELL),
				new ActiveBombRecord("project-1", "WC9", BombType.PROFESSION_XP, "XpUser", nowMillis - 30_000L, nowMillis + 80_000L, BombSource.CHAT_BELL)
			),
			List.of(),
			List.of(),
			List.of(
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_SPEED, true, 0),
				new DashboardBombTypeSettingRecord("project-1", BombType.COMBAT_XP, true, 1),
				new DashboardBombTypeSettingRecord("project-1", BombType.LOOT, false, 2),
				new DashboardBombTypeSettingRecord("project-1", BombType.DUNGEON, false, 3),
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_XP, true, 4),
				new DashboardBombTypeSettingRecord("project-1", BombType.LOOT_CHEST, false, 5)
			),
			List.of(
				new DashboardComboRecord("project-1", "prof", "Profession Pair", List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), DashboardComboSortMode.WORLD, 0, nowMillis, nowMillis)
			)
		);

		assertEquals(5, rendered.size());
		assertEquals(DashboardSlotId.REPORTER_STATUS, rendered.get(0).slotId());
		assertEquals(DashboardSlotId.bomb(BombType.PROFESSION_SPEED), rendered.get(1).slotId());
		assertEquals(DashboardSlotId.bomb(BombType.COMBAT_XP), rendered.get(2).slotId());
		assertEquals(DashboardSlotId.bomb(BombType.PROFESSION_XP), rendered.get(3).slotId());
		assertEquals(DashboardSlotId.combo("prof"), rendered.get(4).slotId());
		assertTrue(rendered.get(4).payloadJson().contains("Profession Pair"));
		assertTrue(rendered.get(4).payloadJson().contains("WC9"));
	}

	@Test
	void disabledBombTypesSuppressComboMatches() {
		long nowMillis = System.currentTimeMillis();
		List<DiscordDashboardRenderer.RenderedDashboardMessage> rendered = renderer.render(
			List.of(
				new ActiveBombRecord("project-1", "WC9", BombType.PROFESSION_SPEED, "SpeedUser", nowMillis - 30_000L, nowMillis + 90_000L, BombSource.CHAT_BELL),
				new ActiveBombRecord("project-1", "WC9", BombType.PROFESSION_XP, "XpUser", nowMillis - 30_000L, nowMillis + 80_000L, BombSource.CHAT_BELL)
			),
			List.of(),
			List.of(),
			List.of(
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_SPEED, false, 0),
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_XP, true, 1)
			),
			List.of(
				new DashboardComboRecord("project-1", "prof", "Profession Pair", List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), DashboardComboSortMode.WORLD, 0, nowMillis, nowMillis)
			)
		);

		assertTrue(rendered.get(2).payloadJson().contains("No matching worlds."));
	}

	@Test
	void comboMatchingUsesSanitizedWorldKeys() {
		long nowMillis = System.currentTimeMillis();
		List<DiscordDashboardRenderer.RenderedDashboardMessage> rendered = renderer.render(
			List.of(
				new ActiveBombRecord("project-1", "EU26", BombType.PROFESSION_SPEED, "TheBeamXD", nowMillis - 30_000L, nowMillis + 20_000L, BombSource.CHAT_BELL),
				new ActiveBombRecord("project-1", "EU26", BombType.PROFESSION_XP, "TheBeamXD", nowMillis - 30_000L, nowMillis + 300_000L, BombSource.CHAT_BELL)
			),
			List.of(),
			List.of(),
			List.of(
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_SPEED, true, 0),
				new DashboardBombTypeSettingRecord("project-1", BombType.PROFESSION_XP, true, 1)
			),
			List.of(
				new DashboardComboRecord("project-1", "prof", "Proff World", List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP), DashboardComboSortMode.LATEST_EXPIRY, 0, nowMillis, nowMillis)
			)
		);

		assertTrue(rendered.get(3).payloadJson().contains("EU26"));
		assertFalse(rendered.get(3).payloadJson().contains("No matching worlds."));
	}

	@Test
	void rendersReporterHierarchySections() {
		long nowMillis = System.currentTimeMillis();
		DiscordDashboardRenderer.RenderedDashboardMessage message = renderer.render(
			List.of(),
			List.of(
				new ReporterDeviceRecord("project-1", 1L, 11L, "user-1", "Leader", "abc123", nowMillis, nowMillis, nowMillis, nowMillis, nowMillis - 100L, nowMillis - 100L),
				new ReporterDeviceRecord("project-1", 2L, 22L, "user-2", "BackupA", "def456", nowMillis, nowMillis, nowMillis, nowMillis, nowMillis - 50L, nowMillis - 50L),
				new ReporterDeviceRecord("project-1", 3L, 33L, "user-3", "BackupB", "ghi789", nowMillis, nowMillis, nowMillis, nowMillis, nowMillis - 25L, nowMillis - 25L),
				new ReporterDeviceRecord("project-1", 4L, 44L, "user-4", "Waiting", "jkl012", nowMillis, nowMillis, nowMillis, 0L, nowMillis - 10L, nowMillis - 10L),
				new ReporterDeviceRecord("project-1", 5L, 55L, "user-5", "Ineligible", "mno345", nowMillis, nowMillis, 0L, 0L, null, null)
			),
			List.of(
				new ReporterChainSlotRecord("project-1", ReporterRole.PRIMARY, 11L, nowMillis - 1_000L, nowMillis, 0),
				new ReporterChainSlotRecord("project-1", ReporterRole.SECONDARY, 22L, nowMillis - 900L, nowMillis, 1),
				new ReporterChainSlotRecord("project-1", ReporterRole.TERTIARY, 33L, nowMillis - 800L, nowMillis, 0)
			),
			List.of(),
			List.of()
		).getFirst();

		assertEquals(DashboardSlotId.REPORTER_STATUS, message.slotId());
		assertTrue(message.payloadJson().contains("Primary"));
		assertTrue(message.payloadJson().contains("Leader [abc123]"));
		assertTrue(message.payloadJson().contains("misses 1/2"));
		assertTrue(message.payloadJson().contains("Ineligible [mno345]"));
	}

	@Test
	void sanitizesDashboardOutputText() {
		long nowMillis = System.currentTimeMillis();
		List<DiscordDashboardRenderer.RenderedDashboardMessage> rendered = renderer.render(
			List.of(
				new ActiveBombRecord("project-1", "WC1", BombType.COMBAT_XP, "\uDBFF\uDFFC\uE001\uDB00\uDC06  ChrisRis", nowMillis - 60_000L, nowMillis + 120_000L, BombSource.CHAT_BELL)
			),
			List.of(
				new ReporterDeviceRecord("project-1", 1L, 11L, "user-1", "\uDBFF\uDFFC\uE001\uDB00\uDC06 Leader", "abc123", nowMillis, nowMillis, nowMillis, nowMillis, nowMillis, nowMillis)
			),
			List.of(
				new ReporterChainSlotRecord("project-1", ReporterRole.PRIMARY, 11L, nowMillis - 1_000L, nowMillis, 0)
			),
			List.of(
				new DashboardBombTypeSettingRecord("project-1", BombType.COMBAT_XP, true, 0)
			),
			List.of()
		);

		assertTrue(rendered.getFirst().payloadJson().contains("- Leader [abc123]"));
		assertTrue(rendered.get(1).payloadJson().contains("- `WC1` - ChrisRis - expires"));
		assertFalse(rendered.get(1).payloadJson().contains("â€¢"));
		assertFalse(rendered.get(1).payloadJson().contains("\uDBFF\uDFFC"));
	}
}
