package dev.deepcolor.mixin.output;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import dev.deepcolor.core.DitherPass;
import dev.deepcolor.core.OutputProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendGpuSurface.class)
abstract class FrontendGpuSurfaceMixin {
	@ModifyArg(
		method = "blitFromTexture",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/api/GpuSurfaceBackend;blitFromTexture(Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"),
		index = 1
	)
	private GpuTextureView deepcolor$dither(GpuTextureView textureView) {
		return DitherPass.apply(textureView);
	}

	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void deepcolor$afterBlit(CommandEncoder commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		OutputProbe.afterBlit(textureView.getWidth(0), textureView.getHeight(0));
	}
}
