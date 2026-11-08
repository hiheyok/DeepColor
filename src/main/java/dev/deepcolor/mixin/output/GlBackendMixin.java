package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import dev.deepcolor.DeepColor;
import dev.deepcolor.core.GlWindowFormat;
import org.lwjgl.sdl.SDLError;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Asks for the window's pixel format: 16-bit float for HDR (Windows composites it as scRGB, macOS
 * needs EDR on top, see MacEdr), 10 bits, or vanilla 8 bits. On Windows a format the driver lacks
 * fails here; on macOS only when the context is created, see GlDeviceMixin.
 */
@Mixin(GlBackend.class)
abstract class GlBackendMixin {
	@WrapOperation(
		method = "createWindow",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J")
	)
	private long deepcolor$createWindow(CharSequence title, int width, int height, long flags, Operation<Long> original) {
		GlWindowFormat.Request format = GlWindowFormat.preferred();
		while (true) {
			GlWindowFormat.request(format);
			long window = original.call(title, width, height, flags);
			GlWindowFormat.Request next = GlWindowFormat.fallback(format);
			if (window != 0L) {
				GlWindowFormat.windowCreated(window);
				return window;
			}
			if (next == null) {
				return 0L;
			}
			DeepColor.LOGGER.warn("Could not create a {} OpenGL window ({}), trying {}", format, SDLError.SDL_GetError(), next);
			format = next;
		}
	}
}
