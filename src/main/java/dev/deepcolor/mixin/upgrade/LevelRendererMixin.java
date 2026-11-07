package dev.deepcolor.mixin.upgrade;

import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.core.Formats;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/TextureTarget;<init>(Ljava/lang/String;IILcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V"),
		index = 3
	)
	private GpuFormat deepcolor$upgradeEntityOutline(GpuFormat format) {
		return Formats.upgrade(format);
	}
}
