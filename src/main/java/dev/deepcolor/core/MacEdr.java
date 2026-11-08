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
 * the window's color space says how the float values are read.
 *
 * <p>The color space is what matters for a correct image. The final pass writes linear light into
 * the float framebuffer, exactly as for the Vulkan scRGB swapchain, and extended linear sRGB is the
 * color space that reads it that way, whether or not the display can go above SDR white: without
 * headroom the same image is simply SDR. In the window's default color space the linear values
 * would be read as gamma-encoded and look far too dark. EDR only lets values above 1.0 through.
 *
 * <p>So both are always switched on for a float window, even when no screen can show EDR right now:
 * an HDR display connected or woken later, or the window moved to one, then gets HDR without a
 * restart. Whether HDR is actually on is followed every frame through SDL's window properties
 * (see {@link Hdr#active()}), not decided here. The output only uses the float encoding when the
 * window really has the linear color space ({@link #hasLinearColorSpace}).
 */
public final class MacEdr {
	private MacEdr() {
	}

	/** Whether the OpenGL view of this window has the EDR flag set. False on other systems and on errors. */
	public static boolean isOn(long sdlWindow) {
		if (!Hdr.isMacOs()) {
			return false;
		}
		try {
			long nsWindow = cocoaWindow(sdlWindow);
			if (nsWindow == 0L) {
				return false;
			}
			long msgSend = msgSend();
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

	/**
	 * Whether this window reads its pixels as extended linear sRGB, asked from the window itself.
	 * This is what the float encoding needs. False on other systems and on errors.
	 */
	public static boolean hasLinearColorSpace(long sdlWindow) {
		if (!Hdr.isMacOs()) {
			return false;
		}
		try {
			long nsWindow = cocoaWindow(sdlWindow);
			long copyName = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CGColorSpaceCopyName");
			long cfEqual = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CFEqual");
			long cfRelease = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "CFRelease");
			long nameSymbol = DynamicLinkLoader.dlsym(DynamicLinkLoader.RTLD_DEFAULT, "kCGColorSpaceExtendedLinearSRGB");
			if (nsWindow == 0L || copyName == 0L || cfEqual == 0L || cfRelease == 0L || nameSymbol == 0L) {
				return false;
			}
			long msgSend = msgSend();
			long nsColorSpace = JNI.invokePPP(nsWindow, sel("colorSpace"), msgSend);
			long cgColorSpace = nsColorSpace == 0L ? 0L : JNI.invokePPP(nsColorSpace, sel("CGColorSpace"), msgSend);
			if (cgColorSpace == 0L) {
				return false;
			}
			long name = JNI.invokePP(cgColorSpace, copyName);
			if (name == 0L) {
				return false;
			}
			try {
				return (JNI.invokePPP(name, MemoryUtil.memGetAddress(nameSymbol), cfEqual) & 0xFF) != 0;
			} finally {
				JNI.invokePV(name, cfRelease);
			}
		} catch (Throwable e) {
			DeepColor.LOGGER.warn("HDR output: could not read the color space of the window", e);
			return false;
		}
	}

	/** Must run on the main thread, which on macOS is the render thread. */
	public static boolean enable(long sdlWindow) {
		try {
			long nsWindow = cocoaWindow(sdlWindow);
			if (nsWindow == 0L) {
				DeepColor.LOGGER.warn("HDR output: SDL gave no Cocoa window");
				return false;
			}
			long msgSend = msgSend();
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
			long nsColorSpace = 0L;
			try {
				nsColorSpace = JNI.invokePPP(ObjCRuntime.objc_getClass("NSColorSpace"), sel("alloc"), msgSend);
				nsColorSpace = JNI.invokePPPP(nsColorSpace, sel("initWithCGColorSpace:"), cgColorSpace, msgSend);
				if (nsColorSpace == 0L) {
					DeepColor.LOGGER.warn("HDR output: could not wrap the extended linear sRGB color space");
					return false;
				}
				JNI.invokePPPV(nsWindow, sel("setColorSpace:"), nsColorSpace, msgSend);
			} finally {
				// The window retains the color space it was given, so both references can go.
				if (nsColorSpace != 0L) {
					JNI.invokePPV(nsColorSpace, sel("release"), msgSend);
				}
				JNI.invokePV(cgColorSpace, release);
			}

			long view = JNI.invokePPP(nsWindow, sel("contentView"), msgSend);
			long setWants = sel("setWantsExtendedDynamicRangeOpenGLSurface:");
			if (view == 0L || (JNI.invokePPPP(view, sel("respondsToSelector:"), setWants, msgSend) & 0xFF) == 0) {
				// Still correct, just without values above SDR white.
				DeepColor.LOGGER.warn("HDR output: the OpenGL view does not support EDR, the image stays within SDR white");
				return true;
			}
			JNI.invokePPV(view, setWants, true, msgSend);
			DeepColor.LOGGER.info("HDR output: linear color space and EDR set for the OpenGL view (screens can show up to {}x SDR white now)",
				String.format(java.util.Locale.ROOT, "%.1f", maximumPotentialEdr(msgSend)));
			return true;
		} catch (Throwable e) {
			DeepColor.LOGGER.error("HDR output: could not enable EDR for the OpenGL view", e);
			return false;
		}
	}

	/** The highest maximumPotentialExtendedDynamicRangeColorComponentValue of all screens, 1 if none. For the log. */
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

	private static long cocoaWindow(long sdlWindow) {
		return SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(sdlWindow), SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L);
	}

	private static long msgSend() {
		return ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
	}

	private static long sel(String name) {
		return ObjCRuntime.sel_getUid(name);
	}
}
