package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanDebug;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import dev.deepcolor.DeepColor;
import dev.deepcolor.core.Hdr;
import java.util.Set;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The scRGB swapchain color space belongs to VK_EXT_swapchain_colorspace, which vanilla does not
 * enable. Added only when HDR output is on, so the default setup stays exactly vanilla.
 */
@Mixin(VulkanInstance.class)
abstract class VulkanInstanceMixin {
	@WrapOperation(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;")
	)
	private VulkanDebug deepcolor$enableColorSpaceExtension(int verbosity, boolean labels, Set<String> available, Set<String> enabled, Operation<VulkanDebug> original) {
		if (Hdr.requested()) {
			if (available.contains(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME)) {
				enabled.add(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
			} else {
				DeepColor.LOGGER.warn("HDR output: the Vulkan driver lacks VK_EXT_swapchain_colorspace");
			}
		}
		return original.call(verbosity, labels, available, enabled);
	}
}
