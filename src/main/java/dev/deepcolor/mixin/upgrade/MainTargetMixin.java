package dev.deepcolor.mixin.upgrade;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.core.Formats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MainTarget.class)
abstract class MainTargetMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;<init>(Ljava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V"),
		index = 1
	)
	private static GpuFormat deepcolor$upgradeMainTarget(GpuFormat format) {
		return Formats.upgrade(format);
	}
}
