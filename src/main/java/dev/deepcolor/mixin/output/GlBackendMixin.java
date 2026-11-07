package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import dev.deepcolor.DeepColor;
import dev.deepcolor.config.DeepColorConfig;
import dev.deepcolor.core.Hdr;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlBackend.class)
abstract class GlBackendMixin {
	@WrapOperation(
		method = "createWindow",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J")
	)
	private long deepcolor$createWindow(CharSequence title, int width, int height, long flags, Operation<Long> original) {
		if (Hdr.requested() && Hdr.isMacOs()) {
			// A float OpenGL drawable on macOS is shown as plain SDR (no EDR), so linear output would
			// look wrong. MoltenVK's extended linear sRGB swapchain does get EDR.
			DeepColor.LOGGER.info("HDR output on macOS needs the Vulkan backend. Using SDR output on OpenGL.");
		} else if (Hdr.requested() && Hdr.displayHdrEnabled()) {
			// Windows composites a 16-bit float OpenGL window as scRGB.
			deepcolor$requestColorBits(16, 16);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FLOATBUFFERS, 1);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE, 0);
			long window = original.call(title, width, height, flags);
			if (window != 0L) {
				return window;
			}
			DeepColor.LOGGER.warn("Could not create a floating-point OpenGL window ({}), trying 10 bits", SDLError.SDL_GetError());
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FLOATBUFFERS, 0);
		}
		if (!DeepColorConfig.get().wants10BitOutput()) {
			deepcolor$requestColorBits(8, 8);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE, 1);
			return original.call(title, width, height, flags);
		}

		deepcolor$requestColorBits(10, 2);
		// Vanilla asks for an sRGB-capable framebuffer, which rules out 10-bit pixel formats on some
		// drivers. Vanilla never enables sRGB writes, so nothing is lost by dropping the request.
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE, 0);
		long window = original.call(title, width, height, flags);
		if (window != 0L) {
			return window;
		}

		// A missing pixel format must never cost the user their game window.
		DeepColor.LOGGER.warn("Could not create a 10-bit OpenGL window ({}), retrying with 8 bits", SDLError.SDL_GetError());
		deepcolor$requestColorBits(8, 8);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE, 1);
		return original.call(title, width, height, flags);
	}

	@Unique
	private static void deepcolor$requestColorBits(int color, int alpha) {
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_RED_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_GREEN_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_BLUE_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_ALPHA_SIZE, alpha);
	}
}
