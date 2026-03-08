package com.bombbellannouncer.relayclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bombbellannouncer.bomb.BombInfo;
import com.bombbellannouncer.bomb.BombSource;
import com.bombbellannouncer.bomb.BombType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DebouncedBombObservationPublisherTest {
	@Test
	void collapsesMultiplePendingUpdatesIntoOnePublish() throws Exception {
		RecordingPublisher delegate = new RecordingPublisher();

		try (DebouncedBombObservationPublisher publisher = new DebouncedBombObservationPublisher(delegate, 50L)) {
			publisher.requestPublish(List.of(sampleBomb(BombType.LOOT, "WC1")), false);
			publisher.requestPublish(List.of(sampleBomb(BombType.LOOT, "WC1"), sampleBomb(BombType.PROFESSION_XP, "WC2")), true);
			publisher.awaitQuiescence(Duration.ofSeconds(2));
		}

		assertEquals(1, delegate.snapshots.size());
		assertEquals(2, delegate.snapshots.getFirst().size());
		assertTrue(delegate.forceHeartbeats.getFirst());
	}

	@Test
	void forceHeartbeatDoesNotOverwritePendingNonEmptySnapshotWithEmptyState() throws Exception {
		RecordingPublisher delegate = new RecordingPublisher();

		try (DebouncedBombObservationPublisher publisher = new DebouncedBombObservationPublisher(delegate, 50L)) {
			publisher.requestPublish(List.of(sampleBomb(BombType.LOOT, "WC1")), false);
			publisher.requestPublish(List.of(), true);
			publisher.awaitQuiescence(Duration.ofSeconds(2));
		}

		assertEquals(1, delegate.snapshots.size());
		assertEquals(1, delegate.snapshots.getFirst().size());
		assertEquals(BombType.LOOT, delegate.snapshots.getFirst().getFirst().bombType());
		assertTrue(delegate.forceHeartbeats.getFirst());
	}

	@Test
	void nonHeartbeatEmptySnapshotStillClearsPendingState() throws Exception {
		RecordingPublisher delegate = new RecordingPublisher();

		try (DebouncedBombObservationPublisher publisher = new DebouncedBombObservationPublisher(delegate, 50L)) {
			publisher.requestPublish(List.of(sampleBomb(BombType.LOOT, "WC1")), false);
			publisher.requestPublish(List.of(), false);
			publisher.awaitQuiescence(Duration.ofSeconds(2));
		}

		assertEquals(1, delegate.snapshots.size());
		assertTrue(delegate.snapshots.getFirst().isEmpty());
	}

	private static BombInfo sampleBomb(BombType bombType, String server) {
		return new BombInfo("ExamplePlayer", bombType, server, 1_000L, bombType.activeMinutes(), BombSource.CHAT_BELL);
	}

	private static final class RecordingPublisher implements BombObservationPublisher {
		private final List<List<BombInfo>> snapshots = new ArrayList<>();
		private final List<Boolean> forceHeartbeats = new ArrayList<>();

		@Override
		public synchronized void requestPublish(Collection<BombInfo> activeBombs, boolean forceHeartbeat) {
			snapshots.add(List.copyOf(activeBombs));
			forceHeartbeats.add(forceHeartbeat);
		}

		@Override
		public void onClientReconnect() {
		}

		@Override
		public void reportBombBell(String notificationKey) {
		}
	}
}
