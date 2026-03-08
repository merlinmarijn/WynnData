package com.bombbellannouncer.modmenu;

import com.bombbellannouncer.BombbellAnnouncerClientMod;
import com.bombbellannouncer.screen.BombbellAnnouncerConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class BombbellAnnouncerModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new BombbellAnnouncerConfigScreen(parent, BombbellAnnouncerClientMod.getConfig());
	}
}
