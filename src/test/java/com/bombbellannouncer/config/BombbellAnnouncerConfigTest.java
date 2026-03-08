package com.bombbellannouncer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.bomb.BombType;
import com.bombbellannouncer.subscription.ComboSubscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class BombbellAnnouncerConfigTest {
	@Test
	void persistsSubscriptions(@TempDir Path tempDir) {
		BombbellAnnouncerConfig config = BombbellAnnouncerConfig.load(tempDir, LoggerFactory.getLogger("test"));
		assertTrue(config.subscribeBombType(BombType.LOOT));
		assertTrue(config.subscribeCombo(new ComboSubscription(List.of(BombType.PROFESSION_XP, BombType.PROFESSION_SPEED))));
		config.save();

		BombbellAnnouncerConfig reloaded = BombbellAnnouncerConfig.load(tempDir, LoggerFactory.getLogger("test"));
		assertEquals(List.of(BombType.LOOT), reloaded.subscribedBombTypes().stream().toList());
		assertEquals(List.of(new ComboSubscription(List.of(BombType.PROFESSION_SPEED, BombType.PROFESSION_XP))), reloaded.subscribedCombos());
	}

	@Test
	void loadsOldConfigsWithoutSubscriptionFields(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("wynndata.json"), """
			{
			  "enabled": true,
			  "relayBaseUrl": "",
			  "projectId": "",
			  "contributorToken": "",
			  "linkedDiscordUser": "",
			  "dashboardName": "",
			  "reporterRole": "INELIGIBLE"
			}
			""");

		BombbellAnnouncerConfig config = BombbellAnnouncerConfig.load(tempDir, LoggerFactory.getLogger("test"));
		assertTrue(config.subscribedBombTypes().isEmpty());
		assertTrue(config.subscribedCombos().isEmpty());
	}
}
