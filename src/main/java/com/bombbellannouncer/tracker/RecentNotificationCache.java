package com.bombbellannouncer.tracker;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RecentNotificationCache {
	private final long ttlMillis;
	private final Map<String, Long> sentAtByKey = new HashMap<>();

	public RecentNotificationCache(long ttlMillis) {
		this.ttlMillis = ttlMillis;
	}

	public boolean shouldSend(String key, long nowMillis) {
		prune(nowMillis);

		Long previousSentAt = sentAtByKey.get(key);
		if (previousSentAt != null && nowMillis - previousSentAt < ttlMillis) {
			return false;
		}

		sentAtByKey.put(key, nowMillis);
		return true;
	}

	public void prune(long nowMillis) {
		Iterator<Map.Entry<String, Long>> iterator = sentAtByKey.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, Long> entry = iterator.next();
			if (nowMillis - entry.getValue() >= ttlMillis) {
				iterator.remove();
			}
		}
	}
}
