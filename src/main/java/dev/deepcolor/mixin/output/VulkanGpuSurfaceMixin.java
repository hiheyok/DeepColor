package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.Hdr;
import dev.deepcolor.core.Hooks;
import dev.deepcolor.core.OutputState;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(VulkanGpuSurface.class)
abstract class VulkanGpuSurfaceMixin {
	@Shadow
	@Final
	private VulkanDevice device;

	@Unique
	private int deepcolor$colorSpace;

	@WrapMethod(method = "pickSwapchainSurfaceFormat")
	private VkSurfaceFormatKHR deepcolor$pickSwapchainFormat(VkSurfaceFormatKHR.Buffer formats, Operation<VkSurfaceFormatKHR> original) {
		List<String> offered = new ArrayList<>();
		for (int i = 0; i < formats.capacity(); i++) {
			offered.add(formats.get(i).format() + "/" + formats.get(i).colorSpace());
		}
		DeepColor.LOGGER.info("Swapchain formats offered (VkFormat/VkColorSpaceKHR): {}", offered);

		// A float format with the vanilla color space is invalid, so scRGB needs the color space hook.
		boolean colorSpaceHook = !Hooks.hasFailed("deepcolor$swapchainColorSpace");
		if (Hdr.requested() && colorSpaceHook && this.device.instance().getEnabledExtensions().contains(EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME) && Hdr.displayHdrEnabled()) {
			for (int i = 0; i < formats.capacity(); i++) {
				VkSurfaceFormatKHR format = formats.get(i);
				if (format.format() == VK10.VK_FORMAT_R16G16B16A16_SFLOAT
					&& format.colorSpace() == EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT
					&& this.deepcolor$canBlitTo(format.format())) {
					this.deepcolor$colorSpace = format.colorSpace();
					OutputState.setVulkanSurface("R16G16B16A16_SFLOAT scRGB", 16, true);
					return format;
				}
			}
			DeepColor.LOGGER.warn("HDR output: no FP16 scRGB swapchain format is offered, using SDR output");
		}

		if (DeepColorConfig.get().wants10BitOutput()) {
			for (int wanted : new int[]{VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32}) {
				for (int i = 0; i < formats.capacity(); i++) {
					VkSurfaceFormatKHR format = formats.get(i);
					// Requiring sRGB nonlinear is what keeps this SDR. HDR color spaces never match.
					if (format.format() == wanted && format.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR && this.deepcolor$canBlitTo(wanted)) {
						String name = wanted == VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32 ? "A2B10G10R10_UNORM_PACK32" : "A2R10G10B10_UNORM_PACK32";
						OutputState.setVulkanSurface(name + " sRGB", 10);
						return format;
					}
				}
			}
		}

		VkSurfaceFormatKHR vanilla = original.call(formats);
		OutputState.setVulkanSurface("VkFormat " + vanilla.format() + " sRGB", 8);
		return vanilla;
	}

	/** Vanilla always creates the swapchain with sRGB nonlinear (0). */
	@ModifyArg(
		method = "configure",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;")
	)
	private int deepcolor$swapchainColorSpace(int colorSpace) {
		return this.deepcolor$colorSpace == 0 ? colorSpace : this.deepcolor$colorSpace;
	}

	/** The surface is written with vkCmdBlitImage, which A2R10G10B10 is not required to support. */
	@Unique
	private boolean deepcolor$canBlitTo(int format) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties properties = VkFormatProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceFormatProperties(this.device.vkDevice().getPhysicalDevice(), format, properties);
			return (properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;
		}
	}
}
