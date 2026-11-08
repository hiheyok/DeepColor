package dev.deepcolor.mixin.output;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.deepcolor.DeepColor;
import dev.deepcolor.core.GlWindowFormat;
import dev.deepcolor.core.Hdr;
import org.lwjgl.sdl.SDLError;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On macOS the pixel format is only chosen here, when the context is created. If the driver has no
 * such format, vanilla would destroy the window and give up on OpenGL, so the next format is tried
 * on the same window instead.
 *
 * <p>Only on macOS. Elsewhere (WGL, GLX/EGL) the window's pixel format is fixed when the window is
 * created, so new attributes cannot change it here: a retry would get a context in the old format
 * while later windows asked for the new one, and wglMakeCurrent refuses a context whose pixel format
 * differs from the window's. Those systems fall back at window creation, in GlBackendMixin.
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
		while (context == 0L && Hdr.isMacOs()) {
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
		if (context != 0L) {
			GlWindowFormat.contextCreated();
		}
		return context;
	}
}
