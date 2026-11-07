package dev.deepcolor;

import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.DitherPass;
import dev.deepcolor.core.PipelineRouter;
import dev.deepcolor.gui.DeepColorCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeepColor implements ClientModInitializer {
	public static final String MOD_ID = "deepcolor";
	public static final Logger LOGGER = LoggerFactory.getLogger("DeepColor");

	/** Both mods claim the swapchain and main target formats. HDR output wins. */
	public static boolean isDisabledByHdrMod() {
		return FabricLoader.getInstance().isModLoaded("hdr_mod");
	}

	@Override
	public void onInitializeClient() {
		if (isDisabledByHdrMod()) {
			return;
		}
		DeepColorConfig.get();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> DeepColorCommand.register(dispatcher));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			DitherPass.close();
			PipelineRouter.close();
		});
	}
}
