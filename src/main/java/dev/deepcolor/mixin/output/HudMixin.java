package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.deepcolor.core.HdrExpandPass;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Records the color the vanilla vignette is drawn with this frame. The vignette multiplies the
 * screen by 1 - color * vignette.png and leaves alpha alone, so the HDR expansion divides it out
 * exactly instead of guessing it from the pixel, which added light (nausea) would throw off.
 */
@Mixin(Hud.class)
abstract class HudMixin {
	@WrapOperation(method = "extractVignette", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;colorFromFloat(FFFF)I"))
	private int deepcolor$recordVignette(float alpha, float red, float green, float blue, Operation<Integer> original) {
		int color = original.call(alpha, red, green, blue);
		HdrExpandPass.vignetteExtracted(color);
		return color;
	}
}
