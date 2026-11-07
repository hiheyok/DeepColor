package dev.deepcolor.mixin.debug;

import dev.deepcolor.gui.DeepColorDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
	@Shadow
	private static Identifier register(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}

	@Inject(method = "<clinit>", at = @At("RETURN"))
	private static void deepcolor$registerEntry(CallbackInfo ci) {
		register(DeepColorDebugEntry.ID, new DeepColorDebugEntry());
	}
}
