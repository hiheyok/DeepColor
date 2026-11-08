package dev.deepcolor.test.mixin;

import dev.deepcolor.core.HdrPerformanceBenchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only frame cadence sampling; disabled during ordinary gametests. */
@Mixin(Minecraft.class)
abstract class PerformanceFrameMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void deepcolortest$frame(boolean renderLevel, CallbackInfo ci) {
        if (Boolean.getBoolean("deepcolor.test.benchmark")) HdrPerformanceBenchmark.frame();
    }
}
