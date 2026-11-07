package dev.deepcolor.mixin.upgrade;

import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.core.Formats;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The lightmap multiplies into everything lit in the world, so at 8 bits its own steps show up as
 * banding in dark scenes no matter how precise the main target is.
 */
@Mixin(Lightmap.class)
abstract class LightmapMixin {
	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),
		index = 2
	)
	private GpuFormat deepcolor$upgradeLightmap(GpuFormat format) {
		return Formats.upgrade(format);
	}
}
