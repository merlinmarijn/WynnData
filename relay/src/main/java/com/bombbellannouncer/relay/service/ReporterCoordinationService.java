package com.bombbellannouncer.relay.service;

import com.bombbellannouncer.protocol.BombBellProofRequest;
import com.bombbellannouncer.protocol.BombSnapshotRequest;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.protocol.ReporterStatusRequest;
import com.bombbellannouncer.protocol.ReporterStatusResponse;
import com.bombbellannouncer.protocol.ReporterSubmitIntentRequest;
import com.bombbellannouncer.protocol.ReporterSubmitIntentResponse;
import com.bombbellannouncer.relay.persistence.RelayStore;
import com.bombbellannouncer.relay.persistence.RelayStore.AuthenticatedDevice;
import com.bombbellannouncer.relay.persistence.RelayStore.ProjectRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterChainSlotRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.ReporterDeviceRecord;
import com.bombbellannouncer.relay.persistence.RelayStore.SubmitWindowRecord;
import com.bombbellannouncer.relay.util.CryptoUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class ReporterCoordinationService implements AutoCloseable {
	public static final long STATUS_STALE_MILLIS = 180_000L;
	public static final long SNAPSHOT_STALE_MILLIS = 10L * 60_000L;
	public static final long SUBMIT_WINDOW_MILLIS = 10_000L;
	private static final int MAX_CHAIN_SIZE = 3;
	private static final int MAX_MISSES = 2;
	private static final List<ReporterRole> CHAIN_ORDER = List.of(ReporterRole.PRIMARY, ReporterRole.SECONDARY, ReporterRole.TERTIARY);

	private final RelayStore store;
	private final DashboardSyncRequester syncRequester;
	private final Logger logger;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "bombbell-relay-reporters");
		thread.setDaemon(true);
		return thread;
	});

	public ReporterCoordinationService(RelayStore store, DashboardSyncRequester syncRequester, Logger logger) {
		this.store = store;
		this.syncRequester = syncRequester;
		this.logger = logger;
	}

	public void start() {
		executor.scheduleWithFixedDelay(this::runHousekeepingSafely, 1L, 1L, TimeUnit.SECONDS);
	}

	public synchronized ActionResult handleStatus(String rawToken, ReporterStatusRequest request, long nowMillis) {
		if (request == null || request.projectId() == null || request.projectId().isBlank()) {
			return new ActionResult(400, ReporterStatusResponse.failure("Invalid reporter status request.", ReporterRole.INELIGIBLE));
		}

		AuthenticatedDevice device = authenticate(request.projectId(), rawToken, nowMillis);
		if (device == null) {
			return new ActionResult(401, ReporterStatusResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE));
		}

		store.markDeviceStatus(device.projectId(), device.credentialId(), nowMillis);
		boolean changed = reconcileProjectState(device.projectId(), nowMillis, Set.of());
		syncRequester.requestSync(device.projectId(), changed);
		return new ActionResult(200, responseFor(device.projectId(), device.credentialId(), "Reporter status updated."));
	}

	public synchronized ActionResult handleBombBellProof(String rawToken, BombBellProofRequest request, long nowMillis) {
		if (request == null || request.projectId() == null || request.projectId().isBlank() || request.notificationKey() == null || request.notificationKey().isBlank()) {
			return new ActionResult(400, ReporterStatusResponse.failure("Invalid bomb-bell proof request.", ReporterRole.INELIGIBLE));
		}

		AuthenticatedDevice device = authenticate(request.projectId(), rawToken, nowMillis);
		if (device == null) {
			return new ActionResult(401, ReporterStatusResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE));
		}

		boolean newlyEligible = store.markBombBellProof(device.projectId(), device.credentialId(), nowMillis);
		boolean changed = reconcileProjectState(device.projectId(), nowMillis, Set.of());
		if (newlyEligible || changed) {
			syncRequester.requestSync(device.projectId(), true);
		}
		return new ActionResult(200, responseFor(device.projectId(), device.credentialId(), "Bomb bell proof accepted."));
	}

	public synchronized SubmitIntentActionResult handleSubmitIntent(String rawToken, ReporterSubmitIntentRequest request, long nowMillis) {
		if (request == null || request.projectId() == null || request.projectId().isBlank() || request.snapshotHash() == null || request.snapshotHash().isBlank()) {
			return new SubmitIntentActionResult(400, ReporterSubmitIntentResponse.failure("Invalid submit intent request.", ReporterRole.INELIGIBLE));
		}

		AuthenticatedDevice device = authenticate(request.projectId(), rawToken, nowMillis);
		if (device == null) {
			return new SubmitIntentActionResult(401, ReporterSubmitIntentResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE));
		}

		SubmitIntentContext context = openOrRefreshSubmitWindow(device.projectId(), request.snapshotHash().trim(), nowMillis);
		if (context.changed()) {
			syncRequester.requestSync(device.projectId(), false);
		}

		return new SubmitIntentActionResult(200, submitIntentResponseFor(device.projectId(), device.credentialId(), "Submit intent accepted."));
	}

	public synchronized SnapshotAuthorization authorizeSnapshot(AuthenticatedDevice device, BombSnapshotRequest request, long nowMillis) {
		boolean changed = reconcileProjectState(device.projectId(), nowMillis, Set.of());
		ReporterStatusResponse currentState = responseFor(device.projectId(), device.credentialId(), "Snapshot rejected.");
		SubmitWindowRecord submitWindow = store.findSubmitWindow(device.projectId());
		if (submitWindow == null) {
			if (changed) {
				syncRequester.requestSync(device.projectId(), false);
			}
			return new SnapshotAuthorization(false, 409, ReporterStatusResponse.failure("No submit window is currently open.", currentState.role()));
		}

		if (request == null || request.submitWindowSequence() <= 0L || request.submitWindowSequence() != submitWindow.sequence()) {
			if (changed) {
				syncRequester.requestSync(device.projectId(), false);
			}
			return new SnapshotAuthorization(false, 409, ReporterStatusResponse.failure("Snapshot submit window is stale.", currentState.role()));
		}

		if (submitWindow.allowedCredentialId() != device.credentialId()) {
			if (changed) {
				syncRequester.requestSync(device.projectId(), false);
			}
			return new SnapshotAuthorization(false, 409, ReporterStatusResponse.failure("Another reporter currently owns this submit window.", currentState.role()));
		}

		List<ReporterChainSlotRecord> currentChain = store.findReporterChain(device.projectId());
		List<ReporterChainSlotRecord> updatedChain = new ArrayList<>();
		for (ReporterChainSlotRecord slot : orderedChain(currentChain)) {
			if (slot.credentialId() == device.credentialId()) {
				updatedChain.add(new ReporterChainSlotRecord(slot.projectId(), slot.role(), slot.credentialId(), slot.assignedAt(), nowMillis, 0));
			} else {
				updatedChain.add(slot);
			}
		}
		if (!sameChain(currentChain, updatedChain)) {
			store.saveReporterChain(device.projectId(), updatedChain, nowMillis);
		}

		store.markSnapshotAccepted(device.projectId(), device.credentialId(), nowMillis);
		store.clearSubmitWindow(device.projectId());
		syncRequester.requestSync(device.projectId(), false);
		return new SnapshotAuthorization(true, 200, ReporterStatusResponse.success("Snapshot accepted.", roleFor(device.projectId(), device.credentialId(), updatedChain), false, 0L));
	}

	public synchronized void runHousekeepingOnce(long nowMillis) {
		for (ProjectRecord project : store.findAllProjects()) {
			if (reconcileProjectState(project.projectId(), nowMillis, Set.of())) {
				syncRequester.requestSync(project.projectId(), false);
			}
		}
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	private void runHousekeepingSafely() {
		try {
			runHousekeepingOnce(System.currentTimeMillis());
		} catch (Exception exception) {
			logger.warn("Failed reporter coordination housekeeping", exception);
		}
	}

	private AuthenticatedDevice authenticate(String projectId, String rawToken, long nowMillis) {
		if (projectId == null || projectId.isBlank() || rawToken == null || rawToken.isBlank()) {
			return null;
		}
		return store.authenticateDevice(projectId.trim(), CryptoUtil.sha256Hex(rawToken.trim()), nowMillis);
	}

	private boolean reconcileProjectState(String projectId, long nowMillis, Set<Long> excludedFromFill) {
		ChainState chainState = reconcileReporterChain(projectId, store.findReporterChain(projectId), nowMillis, excludedFromFill);
		WindowState windowState = reconcileSubmitWindow(projectId, chainState.chain(), nowMillis);
		return chainState.changed() || windowState.changed();
	}

	private SubmitIntentContext openOrRefreshSubmitWindow(String projectId, String snapshotHash, long nowMillis) {
		ChainState chainState = reconcileReporterChain(projectId, store.findReporterChain(projectId), nowMillis, Set.of());
		WindowState windowState = reconcileSubmitWindow(projectId, chainState.chain(), nowMillis);
		boolean changed = chainState.changed() || windowState.changed();
		List<ReporterChainSlotRecord> chain = windowState.chain();
		SubmitWindowRecord submitWindow = windowState.window();
		if (chain.isEmpty()) {
			if (submitWindow != null) {
				store.clearSubmitWindow(projectId);
				changed = true;
			}
			return new SubmitIntentContext(chain, null, changed);
		}

		if (submitWindow != null && snapshotHash.equals(submitWindow.snapshotHash())) {
			return new SubmitIntentContext(chain, submitWindow, changed);
		}

		long sequence = store.nextSubmitWindowSequence(projectId);
		ReporterChainSlotRecord allowedSlot = chain.getFirst();
		SubmitWindowRecord newWindow = new SubmitWindowRecord(
			projectId,
			snapshotHash,
			sequence,
			allowedSlot.credentialId(),
			List.of(),
			nowMillis,
			nowMillis,
			nowMillis
		);
		store.upsertSubmitWindow(newWindow);
		return new SubmitIntentContext(chain, newWindow, true);
	}

	private ChainState reconcileReporterChain(
		String projectId,
		List<ReporterChainSlotRecord> seedChain,
		long nowMillis,
		Set<Long> excludedFromFill
	) {
		List<ReporterChainSlotRecord> persistedChain = store.findReporterChain(projectId);
		List<ReporterDeviceRecord> connectedDevices = store.findConnectedReporterDevices(projectId, nowMillis - STATUS_STALE_MILLIS);
		Map<Long, ReporterDeviceRecord> connectedByCredential = new HashMap<>();
		for (ReporterDeviceRecord device : connectedDevices) {
			connectedByCredential.put(device.credentialId(), device);
		}

		List<ReporterChainSlotRecord> retained = new ArrayList<>();
		Set<Long> assignedCredentialIds = new HashSet<>();
		for (ReporterChainSlotRecord slot : orderedChain(seedChain)) {
			ReporterDeviceRecord device = connectedByCredential.get(slot.credentialId());
			if (device == null || !device.eligible() || excludedFromFill.contains(slot.credentialId()) || isSnapshotStale(slot, device, nowMillis)) {
				continue;
			}
			retained.add(slot);
			assignedCredentialIds.add(slot.credentialId());
		}

		List<ReporterDeviceRecord> waiting = connectedDevices.stream()
			.filter(ReporterDeviceRecord::eligible)
			.filter(device -> !assignedCredentialIds.contains(device.credentialId()))
			.filter(device -> !excludedFromFill.contains(device.credentialId()))
			.sorted(Comparator
				.comparing((ReporterDeviceRecord device) -> device.eligibleAt() == null ? Long.MAX_VALUE : device.eligibleAt())
				.thenComparingLong(ReporterDeviceRecord::credentialId))
			.toList();

		List<ReporterChainSlotRecord> normalized = new ArrayList<>();
		int nextIndex = 0;
		for (ReporterChainSlotRecord slot : retained) {
			if (nextIndex >= MAX_CHAIN_SIZE) {
				break;
			}
			normalized.add(new ReporterChainSlotRecord(
				projectId,
				CHAIN_ORDER.get(nextIndex),
				slot.credentialId(),
				slot.assignedAt(),
				nowMillis,
				Math.max(0, slot.missCount())
			));
			nextIndex++;
		}

		for (ReporterDeviceRecord device : waiting) {
			if (nextIndex >= MAX_CHAIN_SIZE) {
				break;
			}
			normalized.add(new ReporterChainSlotRecord(
				projectId,
				CHAIN_ORDER.get(nextIndex),
				device.credentialId(),
				nowMillis,
				nowMillis,
				0
			));
			nextIndex++;
		}

		boolean changed = !sameChain(persistedChain, normalized);
		if (changed) {
			store.saveReporterChain(projectId, normalized, nowMillis);
		}

		return new ChainState(normalized, connectedDevices, changed);
	}

	private WindowState reconcileSubmitWindow(String projectId, List<ReporterChainSlotRecord> currentChain, long nowMillis) {
		SubmitWindowRecord submitWindow = store.findSubmitWindow(projectId);
		if (submitWindow == null) {
			return new WindowState(currentChain, null, false);
		}

		if (!chainContains(currentChain, submitWindow.allowedCredentialId())) {
			return advanceSubmitWindow(projectId, submitWindow, currentChain, nowMillis, false);
		}

		if (nowMillis - submitWindow.windowStartedAt() >= SUBMIT_WINDOW_MILLIS) {
			return advanceSubmitWindow(projectId, submitWindow, currentChain, nowMillis, true);
		}

		return new WindowState(currentChain, submitWindow, false);
	}

	private WindowState advanceSubmitWindow(
		String projectId,
		SubmitWindowRecord submitWindow,
		List<ReporterChainSlotRecord> currentChain,
		long nowMillis,
		boolean countMiss
	) {
		List<ReporterChainSlotRecord> workingChain = orderedChain(currentChain);
		boolean changed = false;
		List<Long> attemptedCredentialIds = new ArrayList<>(submitWindow.attemptedCredentialIds());
		long previousAllowedCredentialId = submitWindow.allowedCredentialId();

		if (countMiss && !attemptedCredentialIds.contains(previousAllowedCredentialId)) {
			attemptedCredentialIds.add(previousAllowedCredentialId);
		}

		if (countMiss) {
			int slotIndex = indexOfCredential(workingChain, previousAllowedCredentialId);
			if (slotIndex >= 0) {
				ReporterChainSlotRecord currentSlot = workingChain.get(slotIndex);
				int newMissCount = currentSlot.missCount() + 1;
				List<ReporterChainSlotRecord> seededChain = new ArrayList<>(workingChain);
				if (newMissCount >= MAX_MISSES) {
					seededChain.remove(slotIndex);
					ChainState chainState = reconcileReporterChain(projectId, seededChain, nowMillis, Set.of(previousAllowedCredentialId));
					workingChain = chainState.chain();
					changed |= chainState.changed();
				} else {
					seededChain.set(slotIndex, new ReporterChainSlotRecord(
						currentSlot.projectId(),
						currentSlot.role(),
						currentSlot.credentialId(),
						currentSlot.assignedAt(),
						nowMillis,
						newMissCount
					));
					ChainState chainState = reconcileReporterChain(projectId, seededChain, nowMillis, Set.of());
					workingChain = chainState.chain();
					changed |= chainState.changed();
				}
			}
		} else if (!attemptedCredentialIds.contains(previousAllowedCredentialId)) {
			attemptedCredentialIds.add(previousAllowedCredentialId);
		}

		ReporterChainSlotRecord nextAllowedSlot = workingChain.stream()
			.filter(slot -> !attemptedCredentialIds.contains(slot.credentialId()))
			.findFirst()
			.orElse(null);

		if (nextAllowedSlot == null) {
			store.clearSubmitWindow(projectId);
			return new WindowState(workingChain, null, true);
		}

		SubmitWindowRecord updatedWindow = new SubmitWindowRecord(
			projectId,
			submitWindow.snapshotHash(),
			submitWindow.sequence(),
			nextAllowedSlot.credentialId(),
			attemptedCredentialIds,
			submitWindow.createdAt(),
			nowMillis,
			nowMillis
		);
		if (!sameWindow(submitWindow, updatedWindow)) {
			store.upsertSubmitWindow(updatedWindow);
			changed = true;
		}
		return new WindowState(workingChain, updatedWindow, changed);
	}

	private ReporterStatusResponse responseFor(String projectId, long credentialId, String message) {
		ReporterDeviceRecord device = store.findReporterDevice(projectId, credentialId);
		if (device == null) {
			return ReporterStatusResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE);
		}

		List<ReporterChainSlotRecord> chain = store.findReporterChain(projectId);
		SubmitWindowRecord submitWindow = store.findSubmitWindow(projectId);
		ReporterRole role = device.eligible() ? roleFor(projectId, credentialId, chain) : ReporterRole.INELIGIBLE;
		boolean canSubmit = submitWindow != null && submitWindow.allowedCredentialId() == credentialId;
		long submitWindowSequence = submitWindow == null ? 0L : submitWindow.sequence();
		return ReporterStatusResponse.success(message, role, canSubmit, submitWindowSequence);
	}

	private ReporterSubmitIntentResponse submitIntentResponseFor(String projectId, long credentialId, String message) {
		ReporterDeviceRecord device = store.findReporterDevice(projectId, credentialId);
		if (device == null) {
			return ReporterSubmitIntentResponse.failure("Contributor token is invalid.", ReporterRole.INELIGIBLE);
		}

		List<ReporterChainSlotRecord> chain = store.findReporterChain(projectId);
		SubmitWindowRecord submitWindow = store.findSubmitWindow(projectId);
		ReporterRole role = device.eligible() ? roleFor(projectId, credentialId, chain) : ReporterRole.INELIGIBLE;
		boolean canSubmit = submitWindow != null && submitWindow.allowedCredentialId() == credentialId;
		long submitWindowSequence = submitWindow == null ? 0L : submitWindow.sequence();
		return ReporterSubmitIntentResponse.success(message, role, canSubmit, submitWindowSequence);
	}

	private static ReporterRole roleFor(String projectId, long credentialId, List<ReporterChainSlotRecord> chain) {
		for (ReporterChainSlotRecord slot : orderedChain(chain)) {
			if (slot.projectId().equals(projectId) && slot.credentialId() == credentialId) {
				return slot.role();
			}
		}
		return ReporterRole.WAITING;
	}

	private static boolean isSnapshotStale(ReporterChainSlotRecord slot, ReporterDeviceRecord device, long nowMillis) {
		long activityReference = device.lastSnapshotAt() > 0L ? device.lastSnapshotAt() : slot.assignedAt();
		return nowMillis - activityReference >= SNAPSHOT_STALE_MILLIS;
	}

	private static boolean chainContains(List<ReporterChainSlotRecord> chain, long credentialId) {
		return indexOfCredential(chain, credentialId) >= 0;
	}

	private static int indexOfCredential(List<ReporterChainSlotRecord> chain, long credentialId) {
		for (int index = 0; index < chain.size(); index++) {
			if (chain.get(index).credentialId() == credentialId) {
				return index;
			}
		}
		return -1;
	}

	private static List<ReporterChainSlotRecord> orderedChain(List<ReporterChainSlotRecord> chain) {
		List<ReporterChainSlotRecord> ordered = new ArrayList<>(chain);
		ordered.sort(Comparator.comparingInt(slot -> CHAIN_ORDER.indexOf(slot.role())));
		return ordered;
	}

	private static boolean sameChain(List<ReporterChainSlotRecord> left, List<ReporterChainSlotRecord> right) {
		List<ReporterChainSlotRecord> orderedLeft = orderedChain(left);
		List<ReporterChainSlotRecord> orderedRight = orderedChain(right);
		if (orderedLeft.size() != orderedRight.size()) {
			return false;
		}

		for (int index = 0; index < orderedLeft.size(); index++) {
			ReporterChainSlotRecord leftSlot = orderedLeft.get(index);
			ReporterChainSlotRecord rightSlot = orderedRight.get(index);
			if (leftSlot.role() != rightSlot.role()
				|| leftSlot.credentialId() != rightSlot.credentialId()
				|| leftSlot.assignedAt() != rightSlot.assignedAt()
				|| leftSlot.missCount() != rightSlot.missCount()) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameWindow(SubmitWindowRecord left, SubmitWindowRecord right) {
		return Objects.equals(left.snapshotHash(), right.snapshotHash())
			&& left.sequence() == right.sequence()
			&& left.allowedCredentialId() == right.allowedCredentialId()
			&& Objects.equals(left.attemptedCredentialIds(), right.attemptedCredentialIds())
			&& left.createdAt() == right.createdAt()
			&& left.windowStartedAt() == right.windowStartedAt();
	}

	public record ActionResult(int statusCode, ReporterStatusResponse response) {
	}

	public record SubmitIntentActionResult(int statusCode, ReporterSubmitIntentResponse response) {
	}

	public record SnapshotAuthorization(boolean allowed, int statusCode, ReporterStatusResponse response) {
	}

	private record ChainState(List<ReporterChainSlotRecord> chain, List<ReporterDeviceRecord> connectedDevices, boolean changed) {
	}

	private record WindowState(List<ReporterChainSlotRecord> chain, SubmitWindowRecord window, boolean changed) {
	}

	private record SubmitIntentContext(List<ReporterChainSlotRecord> chain, SubmitWindowRecord submitWindow, boolean changed) {
	}
}
