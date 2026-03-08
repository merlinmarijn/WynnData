package com.bombbellannouncer.relay.service;

public interface DashboardSyncRequester {
	void requestSync(String projectId, boolean force);
}
