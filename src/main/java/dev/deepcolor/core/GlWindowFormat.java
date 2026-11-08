package dev.deepcolor.core;

import dev.deepcolor.config.DeepColorConfig;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * The pixel format asked for the OpenGL windows, and the fallbacks when a driver refuses it. A
 * format can fail at two points: Windows picks the pixel format when a window is created, macOS
 * only when the context is created (SDL's Cocoa backend builds the NSOpenGLPixelFormat there). Both
 * points retry with the next format, because a format the driver does not offer must never cost the
 * user their game window.
 *
 * <p>GlDevice creates its one context on a hidden utility window, before the game window exists,
 * and every later window (a hidden test window, then the game window) shares that context. So the
 * format is settled with the context, and later windows ask for that same format.
 */
public final class GlWindowFormat {
	public enum Request {
		/** 16-bit float, for HDR output. */
		FLOAT,
		TEN_BIT,
		EIGHT_BIT
	}

	/**
	 * For tests: context creation fails for the float format, as it would on a macOS driver without
	 * one. Only on macOS, the only system that picks the format at context creation (see GlDeviceMixin).
	 */
	private static final boolean SIMULATE_FLOAT_CONTEXT_FAILURE = Boolean.getBoolean("deepcolor.test.failGlFloatContext");

	private static Request requested = Request.EIGHT_BIT;
	/** The format the context was created with, once it exists. */
	private static @Nullable Request settled;

	private GlWindowFormat() {
	}

	/**
	 * The first format to try for a window: the context's format once it exists, otherwise float for
	 * HDR, then 10 bits if configured, then vanilla 8 bits.
	 */
	public static Request preferred() {
		if (settled != null) {
			return settled;
		}
		if (Hdr.requested() && Hdr.displayHdrEnabled()) {
			return Request.FLOAT;
		}
		return DeepColorConfig.get().wants10BitOutput() ? Request.TEN_BIT : Request.EIGHT_BIT;
	}

	/** The next format to try after this one failed, or null after vanilla 8 bits. */
	public static @Nullable Request fallback(Request failed) {
		return switch (failed) {
			case FLOAT -> DeepColorConfig.get().wants10BitOutput() ? Request.TEN_BIT : Request.EIGHT_BIT;
			case TEN_BIT -> Request.EIGHT_BIT;
			case EIGHT_BIT -> null;
		};
	}

	/** Sets the SDL attributes for a format. They apply to the next window and the next context. */
	public static void request(Request format) {
		requested = format;
		int color = format == Request.FLOAT ? 16 : format == Request.TEN_BIT ? 10 : 8;
		int alpha = format == Request.FLOAT ? 16 : format == Request.TEN_BIT ? 2 : 8;
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_RED_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_GREEN_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_BLUE_SIZE, color);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_ALPHA_SIZE, alpha);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FLOATBUFFERS, format == Request.FLOAT ? 1 : 0);
		// Vanilla asks for an sRGB-capable framebuffer, which rules out 10-bit and float pixel formats
		// on some drivers. Vanilla never enables sRGB writes, so nothing is lost by dropping it there.
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_FRAMEBUFFER_SRGB_CAPABLE, format == Request.EIGHT_BIT ? 1 : 0);
	}

	public static Request requested() {
		return requested;
	}

	/** Whether context creation should pretend to fail for the current format. */
	public static boolean simulateContextFailure() {
		return SIMULATE_FLOAT_CONTEXT_FAILURE && requested == Request.FLOAT && Hdr.isMacOs();
	}

	/** Called once the context exists, which settles the format for every window. */
	public static void contextCreated() {
		settled = requested;
	}

	/**
	 * Called for every window created successfully. Windows made after a float context (the game
	 * window among them) get EDR on macOS. Never before the context exists: with an 8-bit fallback, the
	 * extended linear color space would show the image far too dark.
	 */
	public static void windowCreated(long window) {
		if (settled == Request.FLOAT && Hdr.isMacOs()) {
			MacEdr.enable(window);
		}
	}
}
