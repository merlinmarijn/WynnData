package com.bombbellannouncer.relayclient;

import com.bombbellannouncer.bomb.BombInfo;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DebouncedBombObservationPublisher implements BombObservationPublisher, AutoCloseable {
	private final BombObservationPublisher delegate;
	private final ScheduledExecutorService executor;
	private final long debounceMillis;
	private final Object lock = new Object();

	private List<BombInfo> pendingSnapshot;
	private boolean pendingForceHeartbeat;
	private ScheduledFuture<?> scheduledPublish;
	private boolean draining;

	public DebouncedBombObservationPublisher(BombObservationPublisher delegate, long debounceMillis) {
		this(
			delegate,
			Executors.newSingleThreadScheduledExecutor(new ObservationThreadFactory()),
			debounceMillis
		);
	}

	DebouncedBombObservationPublisher(BombObservationPublisher delegate, ScheduledExecutorService executor, long debounceMillis) {
		this.delegate = delegate;
		this.executor = executor;
		this.debounceMillis = debounceMillis;
	}

	@Override
	public void requestPublish(Collection<BombInfo> activeBombs, boolean forceHeartbeat) {
		synchronized (lock) {
			List<BombInfo> incomingSnapshot = List.copyOf(activeBombs);
			if (pendingSnapshot == null || shouldReplacePendingSnapshot(pendingSnapshot, incomingSnapshot, forceHeartbeat)) {
				pendingSnapshot = incomingSnapshot;
			}
			pendingForceHeartbeat |= forceHeartbeat;

			if (draining || (scheduledPublish != null && !scheduledPublish.isDone())) {
				return;
			}

			scheduledPublish = executor.schedule(this::drainPendingPublishes, debounceMillis, TimeUnit.MILLISECONDS);
		}
	}

	private static boolean shouldReplacePendingSnapshot(List<BombInfo> currentSnapshot, List<BombInfo> incomingSnapshot, boolean forceHeartbeat) {
		if (incomingSnapshot.isEmpty() && forceHeartbeat && !currentSnapshot.isEmpty()) {
			return false;
		}

		return true;
	}

	@Override
	public void reportBombBell(String notificationKey) {
		executor.execute(() -> delegate.reportBombBell(notificationKey));
	}

	@Override
	public void onClientReconnect() {
		executor.execute(delegate::onClientReconnect);
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	void awaitQuiescence(Duration timeout) throws Exception {
		long deadlineNanos = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadlineNanos) {
			synchronized (lock) {
				boolean idle = !draining && (scheduledPublish == null || scheduledPublish.isDone()) && !pendingForceHeartbeat && pendingSnapshot == null;
				if (idle) {
					CompletableFuture<Void> marker = new CompletableFuture<>();
					executor.execute(() -> marker.complete(null));
					marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
					return;
				}
			}

			Thread.sleep(10L);
		}

		throw new TimeoutException("Publisher did not become idle before timeout");
	}

	private void drainPendingPublishes() {
		while (true) {
			List<BombInfo> snapshot;
			boolean forceHeartbeat;

			synchronized (lock) {
				draining = true;
				scheduledPublish = null;
				snapshot = pendingSnapshot;
				forceHeartbeat = pendingForceHeartbeat;
				pendingSnapshot = null;
				pendingForceHeartbeat = false;
			}

			if (snapshot != null) {
				delegate.requestPublish(snapshot, forceHeartbeat);
			}

			synchronized (lock) {
				if (pendingSnapshot == null && !pendingForceHeartbeat) {
					draining = false;
					return;
				}
			}
		}
	}

	private static final class ObservationThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "wynndata-relay");
			thread.setDaemon(true);
			return thread;
		}
	}
}
