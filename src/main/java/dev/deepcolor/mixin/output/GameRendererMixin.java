package dev.deepcolor.mixin.output;

import dev.deepcolor.core.HdrExpandPass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After applyPostEffects the world is complete (hand, outlines, post effects and shader packs
 * included) and the interface has not been drawn yet. Only reached when a world is rendered.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(
		method = "render()V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER)
	)
	private void deepcolor$expandWorldBrightness(CallbackInfo ci) {
		HdrExpandPass.apply(((GameRenderer) (Object) this).mainRenderTarget());
	}
}
