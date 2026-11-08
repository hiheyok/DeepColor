package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.deepcolor.DeepColor;
import dev.deepcolor.core.GlWindowFormat;
import org.lwjgl.sdl.SDLError;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On macOS the pixel format is only chosen here, when the context is created. If the driver has no
 * such format, vanilla would destroy the window and give up on OpenGL, so the next format is tried
 * on the same window instead.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
abstract class GlDeviceMixin {
	@WrapOperation(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_CreateContext(J)J")
	)
	private long deepcolor$createContext(long window, Operation<Long> original) {
		GlWindowFormat.Request format = GlWindowFormat.requested();
		long context = GlWindowFormat.simulateContextFailure() ? 0L : original.call(window);
		while (context == 0L) {
			GlWindowFormat.Request next = GlWindowFormat.fallback(format);
			if (next == null) {
				return 0L;
			}
			DeepColor.LOGGER.warn("Could not create an OpenGL context for {} ({}), trying {}", format,
				GlWindowFormat.simulateContextFailure() ? "simulated by deepcolor.test.failGlFloatContext" : SDLError.SDL_GetError(), next);
			GlWindowFormat.request(next);
			format = next;
			context = original.call(window);
		}
		GlWindowFormat.contextCreated();
		return context;
	}
}
