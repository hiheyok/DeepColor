package dev.deepcolor.core;

import dev.deepcolor.DeepColor;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

/**
 * EDR (HDR) for an OpenGL window on macOS. SDL creates the window, but only Metal layers get EDR from
 * it. An OpenGL view has to opt in itself with {@code wantsExtendedDynamicRangeOpenGLSurface}, and
 * the window's color space says how the float values are read. Extended linear sRGB makes the
 * drawable mean the same as the Vulkan scRGB swapchain, so the same encoding works for both. With
 * the window's default color space, macOS reads the linear values as gamma-encoded, which comes out far
 * too dark and oversaturated.
 */
public final class MacEdr {
	private static boolean enabled;

	private MacEdr() {
	}

	/** True once the OpenGL view of the game window has EDR switched on. */
	public static boolean enabled() {
		return enabled;
	}

	/** Must run on the main thread, which on macOS is the render thread. */
	public static boolean enable(long sdlWindow) {
		try {
			long nsWindow = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow), SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
			if (nsWindow == 0L) {
				DeepColor.LOGGER.warn("HDR output: SDL gave no Cocoa window");
				return false;
			}
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long view = JNI.invokePPP(nsWindow, sel("contentView"), msgSend);
			long setWants = sel("setWantsExtendedDynamicRangeOpenGLSurface:");
			if (view == 0L || (JNI.invokePPPP(view, sel("respondsToSelector:"), setWants, msgSend) & 0xFF) == 0) {
				DeepColor.LOGGER.warn("HDR output: the OpenGL view does not support EDR");
				return false;
			}
			JNI.invokePPV(view, setWants, true, msgSend);

			long create = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CGColorSpaceCreateWithName");
			long release = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CGColorSpaceRelease");
			long nameSymbol = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "kCGColorSpaceExtendedLinearSRGB");
			if (create == 0L || release == 0L || nameSymbol == 0L) {
				DeepColor.LOGGER.warn("HDR output: CoreGraphics color space functions not found");
				return false;
			}
			long cgColorSpace = JNI.invokePP(MemoryUtil.memGetAddress(nameSymbol), create);
			long nsColorSpace = JNI.invokePPP(ObjCRuntime.objc_getClass("NSColorSpace"), sel("alloc"), msgSend);
			nsColorSpace = JNI.invokePPPP(nsColorSpace, sel("initWithCGColorSpace:"), cgColorSpace, msgSend);
			JNI.invokePPPV(nsWindow, sel("setColorSpace:"), nsColorSpace, msgSend);
			// The window retains the color space, so both references can go.
			JNI.invokePPV(nsColorSpace, sel("release"), msgSend);
			JNI.invokePV(cgColorSpace, release);
			enabled = true;
			DeepColor.LOGGER.info("HDR output: EDR enabled for the OpenGL view");
			return true;
		} catch (Throwable e) {
			DeepColor.LOGGER.error("HDR output: could not enable EDR for the OpenGL view", e);
			return false;
		}
	}

	private static long sel(String name) {
		return ObjCRuntime.sel_getUid(name);
	}
}
