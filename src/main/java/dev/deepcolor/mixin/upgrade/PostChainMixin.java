package dev.deepcolor.mixin.upgrade;

import com.mojang.renderpearl.api.GpuFormat;
import dev.deepcolor.core.Formats;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Post chains include the blur behind menus, which bands badly over dark scenes at 8 bits. */
@Mixin(PostChain.class)
abstract class PostChainMixin {
	@ModifyArg(
		method = "addToFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;<init>(Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/GpuFormat;)V"),
		index = 1
	)
	private GpuFormat deepcolor$upgradePostTargets(GpuFormat format) {
		return Formats.upgrade(format);
	}
}
