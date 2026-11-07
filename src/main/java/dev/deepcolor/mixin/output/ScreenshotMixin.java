package dev.deepcolor.mixin.output;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.deepcolor.core.DitherPass;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Screenshot reads every pixel as four RGBA8 bytes, so anything else gives a corrupt image. This
 * also covers world icons and huge screenshots, which go through the same method.
 */
@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
	@ModifyVariable(
		method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private static RenderTarget deepcolor$takeScreenshot(RenderTarget target) {
		return DitherPass.flattenForReadback(target);
	}
}
