package com.bombbellannouncer.relay.discord;

import com.bombbellannouncer.protocol.BombType;

public final class DashboardSlotId {
	public static final String REPORTER_STATUS = "REPORTER_STATUS";
	private static final String BOMB_PREFIX = "BOMB_";
	private static final String COMBO_PREFIX = "COMBO:";

	private DashboardSlotId() {
	}

	public static String bomb(BombType bombType) {
		return BOMB_PREFIX + bombType.name();
	}

	public static String combo(String normalizedName) {
		return COMBO_PREFIX + normalizedName;
	}
}
