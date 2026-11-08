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

	private MacEdr() {
	}

	/**
	 * Whether the OpenGL view of this window has EDR switched on, asked from the view itself. False
	 * on other systems and on any error.
	 */
	public static boolean isOn(long sdlWindow) {
		if (!Hdr.isMacOs()) {
			return false;
		}
		try {
			long nsWindow = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow), SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
			if (nsWindow == 0L) {
				return false;
			}
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long view = JNI.invokePPP(nsWindow, sel("contentView"), msgSend);
			long wants = sel("wantsExtendedDynamicRangeOpenGLSurface");
			if (view == 0L || (JNI.invokePPPP(view, sel("respondsToSelector:"), wants, msgSend) & 0xFF) == 0) {
				return false;
			}
			return (JNI.invokePPP(view, wants, msgSend) & 0xFF) != 0;
		} catch (Throwable e) {
			DeepColor.LOGGER.warn("HDR output: could not read the EDR state of the OpenGL view", e);
			return false;
		}
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
			// The view accepts the EDR flag on any Mac, so reading it back proves nothing. What counts
			// is whether a screen can show values above SDR white at all. Any screen, not just the
			// window's: the game opens on the primary display and may be moved to an HDR one.
			double potential = maximumPotentialEdr(msgSend);
			if (potential <= 1.0) {
				DeepColor.LOGGER.info("HDR output: no screen can show EDR (potential headroom {}), keeping the OpenGL view SDR", potential);
				return false;
			}
			long view = JNI.invokePPP(nsWindow, sel("contentView"), msgSend);
			long setWants = sel("setWantsExtendedDynamicRangeOpenGLSurface:");
			if (view == 0L || (JNI.invokePPPP(view, sel("respondsToSelector:"), setWants, msgSend) & 0xFF) == 0) {
				DeepColor.LOGGER.warn("HDR output: the OpenGL view does not support EDR");
				return false;
			}
			// The extended linear color space and EDR only work together: EDR in the default color
			// space shows the linear image far too dark, the linear color space without EDR washes it
			// out. So the color space goes first and is put back if EDR then does not turn on.
			long create = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CGColorSpaceCreateWithName");
			long release = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CGColorSpaceRelease");
			long nameSymbol = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "kCGColorSpaceExtendedLinearSRGB");
			if (create == 0L || release == 0L || nameSymbol == 0L) {
				DeepColor.LOGGER.warn("HDR output: CoreGraphics color space functions not found");
				return false;
			}
			long cgColorSpace = JNI.invokePP(MemoryUtil.memGetAddress(nameSymbol), create);
			if (cgColorSpace == 0L) {
				DeepColor.LOGGER.warn("HDR output: could not create the extended linear sRGB color space");
				return false;
			}
			long nsColorSpace = JNI.invokePPP(ObjCRuntime.objc_getClass("NSColorSpace"), sel("alloc"), msgSend);
			nsColorSpace = JNI.invokePPPP(nsColorSpace, sel("initWithCGColorSpace:"), cgColorSpace, msgSend);
			if (nsColorSpace == 0L) {
				JNI.invokePV(cgColorSpace, release);
				DeepColor.LOGGER.warn("HDR output: could not wrap the extended linear sRGB color space");
				return false;
			}
			// The current color space, kept alive until EDR is known to be on. It may be nil, which
			// is then what gets put back.
			long previous = JNI.invokePPP(nsWindow, sel("colorSpace"), msgSend);
			if (previous != 0L) {
				JNI.invokePPP(previous, sel("retain"), msgSend);
			}
			JNI.invokePPPV(nsWindow, sel("setColorSpace:"), nsColorSpace, msgSend);
			// The window retains the color space, so both references can go.
			JNI.invokePPV(nsColorSpace, sel("release"), msgSend);
			JNI.invokePV(cgColorSpace, release);
			boolean on = false;
			try {
				JNI.invokePPV(view, setWants, true, msgSend);
				on = (JNI.invokePPP(view, sel("wantsExtendedDynamicRangeOpenGLSurface"), msgSend) & 0xFF) != 0;
			} finally {
				if (!on) {
					JNI.invokePPPV(nsWindow, sel("setColorSpace:"), previous, msgSend);
				}
				if (previous != 0L) {
					JNI.invokePPV(previous, sel("release"), msgSend);
				}
			}
			if (!on) {
				DeepColor.LOGGER.warn("HDR output: the OpenGL view did not accept EDR, color space restored");
				return false;
			}
			DeepColor.LOGGER.info("HDR output: EDR enabled for the OpenGL view");
			return true;
		} catch (Throwable e) {
			DeepColor.LOGGER.error("HDR output: could not enable EDR for the OpenGL view", e);
			return false;
		}
	}

	/** The highest maximumPotentialExtendedDynamicRangeColorComponentValue of all screens, 1 if none. */
	private static double maximumPotentialEdr(long msgSend) {
		long screens = JNI.invokePPP(ObjCRuntime.objc_getClass("NSScreen"), sel("screens"), msgSend);
		if (screens == 0L) {
			return 1.0;
		}
		long count = JNI.invokePPP(screens, sel("count"), msgSend);
		long potentialSelector = sel("maximumPotentialExtendedDynamicRangeColorComponentValue");
		double best = 1.0;
		for (long i = 0; i < count; i++) {
			long screen = JNI.invokePPPP(screens, sel("objectAtIndex:"), i, msgSend);
			if (screen != 0L && (JNI.invokePPPP(screen, sel("respondsToSelector:"), potentialSelector, msgSend) & 0xFF) != 0) {
				// CGFloat is a double on every 64-bit Mac, returned in a float register by objc_msgSend.
				best = Math.max(best, JNI.invokePPD(screen, potentialSelector, msgSend));
			}
		}
		return best;
	}

	private static long sel(String name) {
		return ObjCRuntime.sel_getUid(name);
	}
}
