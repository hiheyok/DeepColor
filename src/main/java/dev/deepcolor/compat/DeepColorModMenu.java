package dev.deepcolor.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.deepcolor.DeepColor;
import dev.deepcolor.gui.DeepColorConfigScreen;

public final class DeepColorModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return DeepColor.isDisabledByHdrMod() ? parent -> null : DeepColorConfigScreen::new;
	}
}
