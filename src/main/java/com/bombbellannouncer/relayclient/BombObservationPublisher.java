package com.bombbellannouncer.relayclient;

import com.bombbellannouncer.bomb.BombInfo;
import java.util.Collection;

public interface BombObservationPublisher {
	void requestPublish(Collection<BombInfo> activeBombs, boolean forceHeartbeat);

	void reportBombBell(String notificationKey);

	void onClientReconnect();
}
