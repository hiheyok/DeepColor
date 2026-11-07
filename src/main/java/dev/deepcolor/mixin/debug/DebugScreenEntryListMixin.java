package dev.deepcolor.mixin.debug;

import dev.deepcolor.gui.DeepColorDebugEntry;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** New entries default to hidden. Show this one unless the user has configured it otherwise. */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin {
	@Shadow
	@Final
	private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Inject(method = "rebuildCurrentList", at = @At("HEAD"))
	private void deepcolor$enableEntry(CallbackInfo ci) {
		this.allStatuses.putIfAbsent(DeepColorDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
	}
}
